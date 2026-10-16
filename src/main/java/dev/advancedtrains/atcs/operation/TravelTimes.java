package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.network.StopEdits;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Learns, per service, how long trains take between consecutive stops, how long they stand at
 * each stop, which platform they use, and how often they leave. Values are exponentially smoothed
 * so they follow timetable changes; isolated outliers (a train held at a signal) move them only
 * slightly, and the usual spread around each value is kept so a train can be called late.
 *
 * <p>Every travel time is also kept by the physical stretch it was measured on, from one platform
 * to another, so a new service on the same track (an express, a diversion) has an estimate before
 * its own trains have run. A segment that spans stops without triggers is learned by sharing the
 * measured time out over its parts by track distance.
 *
 * <p>All data for a service is discarded when its stop list changes.
 */
public final class TravelTimes {
    static final double MAX_SAMPLE_SECONDS = Duration.ofHours(1).toSeconds();
    private static final int OUTLIER_MIN_SAMPLES = 3;
    private static final double OUTLIER_FACTOR = 3.0;
    /** The longest gap between two departures that still counts as an interval, in seconds. */
    static final double MAX_INTERVAL_SECONDS = Duration.ofHours(1).toSeconds();
    /** Stops a measured arrival may be ahead of the last reported one and still be learned. */
    static final int MAX_SPAN = 4;

    private double smoothing;
    private final Map<ServiceKey, ServiceTimes> services = new HashMap<>();
    /** Travel times by physical stretch: {@code from-station:platform>to-station:platform} and {@code from-station>to-station}. */
    private final Map<String, Value> shared = new HashMap<>();
    /** When each service last left each stop, for intervals; not saved. */
    private final Map<ServiceKey, Map<Integer, java.time.Instant>> lastDepartures = new HashMap<>();
    private RouteMeasure measure = RouteMeasure.UNKNOWN;
    private double assumedDwellSeconds = 10;
    private boolean dirty;

    public TravelTimes(double smoothing) {
        setSmoothing(smoothing);
    }

    /** Weight of a new measurement, in (0, 1]. */
    public void setSmoothing(double smoothing) {
        if (!(smoothing > 0 && smoothing <= 1)) {
            throw new IllegalArgumentException("smoothing must be in (0, 1]");
        }
        this.smoothing = smoothing;
    }

    /** Track lengths, for sharing a measured time out over the stops it spans. */
    public void setMeasure(RouteMeasure newMeasure) {
        measure = newMeasure;
    }

    /** The stand time assumed at a stop nobody has reported, when sharing a measured time out. */
    public void setAssumedDwell(double seconds) {
        assumedDwellSeconds = Math.max(0, seconds);
    }

    public void record(JourneyEvent event, Network network) {
        switch (event) {
            case JourneyEvent.Arrived arrived -> {
                recordSegment(network, arrived.service(), arrived.fromIndex(), arrived.stopIndex(), arrived.platform().platformId(),
                        arrived.travelTime());
                recordPlatform(network, arrived.service(), arrived.stopIndex(), arrived.platform().platformId());
            }
            case JourneyEvent.Passed passed when passed.stopIndex() >= 0 ->
                    recordSegment(network, passed.service(), passed.fromIndex(), passed.stopIndex(), passed.platform().platformId(),
                            passed.travelTime());
            case JourneyEvent.Departed departed -> {
                if (departed.dwell() != null) {
                    times(network, departed.service()).ifPresent(times -> {
                        if (times.dwell.update(departed.stopIndex(), departed.dwell().toMillis() / 1000.0)) {
                            dirty = true;
                        }
                    });
                }
                recordInterval(network, departed.service(), departed.stopIndex(), departed.at());
            }
            default -> {
            }
        }
    }

    private void recordSegment(Network network, ServiceKey key, int fromIndex, int toIndex, String toPlatform,
                               @Nullable Duration travelTime) {
        if (fromIndex < 0 || travelTime == null) {
            return;
        }
        Service service = network.service(key).orElse(null);
        ServiceTimes times = service == null ? null : times(network, key).orElse(null);
        if (times == null) {
            return;
        }
        double seconds = travelTime.toMillis() / 1000.0;
        if (service.nextIndex(fromIndex) == toIndex) {
            if (times.segment.update(fromIndex, seconds)) {
                dirty = true;
            }
            recordShared(service, times, fromIndex, toIndex, toPlatform, seconds);
            return;
        }
        // The arrival skipped stops without triggers: share the time out by track length, less
        // the usual stand at each stop in between.
        List<Integer> chain = new ArrayList<>();
        int cursor = fromIndex;
        for (int step = 0; step < MAX_SPAN && cursor >= 0 && cursor != toIndex; step++) {
            chain.add(cursor);
            cursor = service.nextIndex(cursor);
        }
        if (cursor != toIndex || chain.size() < 2) {
            return;
        }
        double moving = seconds;
        for (int index = 1; index < chain.size(); index++) {
            int stop = chain.get(index);
            if (service.stop(stop).stops()) {
                moving -= times.dwell.get(stop).orElse(assumedDwellSeconds);
            }
        }
        if (moving <= 0) {
            return;
        }
        double[] lengths = new double[chain.size()];
        double total = 0;
        boolean measured = true;
        for (int index = 0; index < chain.size(); index++) {
            OptionalDouble length = measure.segmentBlocks(service, chain.get(index));
            if (length.isEmpty() || length.getAsDouble() <= 0) {
                measured = false;
                break;
            }
            lengths[index] = length.getAsDouble();
            total += lengths[index];
        }
        for (int index = 0; index < chain.size(); index++) {
            double share = measured ? moving * lengths[index] / total : moving / chain.size();
            if (times.segment.update(chain.get(index), share)) {
                dirty = true;
            }
        }
    }

    /** Keys a stretch of track by its stations and platforms, so services on the same stretch share samples. */
    private void recordShared(Service service, ServiceTimes times, int fromIndex, int toIndex, @Nullable String toPlatform, double seconds) {
        String fromStation = service.stop(fromIndex).stationId();
        String toStation = service.stop(toIndex).stationId();
        String fromPlatform = service.stop(fromIndex).platformId() != null ? service.stop(fromIndex).platformId()
                : fromIndex < times.platforms.length ? times.platforms[fromIndex] : null;
        if (fromPlatform != null && toPlatform != null) {
            shared.computeIfAbsent(fromStation + ":" + fromPlatform + ">" + toStation + ":" + toPlatform, ignored -> new Value()).update(seconds);
        }
        shared.computeIfAbsent(fromStation + ">" + toStation, ignored -> new Value()).update(seconds);
        dirty = true;
    }

    private void recordInterval(Network network, ServiceKey key, int stopIndex, java.time.Instant at) {
        Map<Integer, java.time.Instant> last = lastDepartures.computeIfAbsent(key, ignored -> new HashMap<>());
        java.time.Instant previous = last.put(stopIndex, at);
        if (previous == null) {
            return;
        }
        double seconds = Duration.between(previous, at).toMillis() / 1000.0;
        if (seconds <= 0 || seconds > MAX_INTERVAL_SECONDS) {
            return;
        }
        times(network, key).ifPresent(times -> {
            if (times.interval.update(stopIndex, seconds)) {
                dirty = true;
            }
        });
    }

    /**
     * Seconds from leaving stop {@code fromIndex} until reaching the next stop as learned on the
     * same stretch of track by any service: by platforms when the service's platforms are known,
     * else between the two stations. Empty when no train has run the stretch.
     */
    public OptionalDouble sharedSegmentSeconds(Service service, int fromIndex) {
        int next = service.nextIndex(fromIndex);
        if (fromIndex < 0 || next < 0) {
            return OptionalDouble.empty();
        }
        String fromStation = service.stop(fromIndex).stationId();
        String toStation = service.stop(next).stationId();
        String fromPlatform = service.stop(fromIndex).platformId() != null ? service.stop(fromIndex).platformId()
                : usualPlatform(service, fromIndex).orElse(null);
        String toPlatform = service.stop(next).platformId() != null ? service.stop(next).platformId()
                : usualPlatform(service, next).orElse(null);
        if (fromPlatform != null && toPlatform != null) {
            Value exact = shared.get(fromStation + ":" + fromPlatform + ">" + toStation + ":" + toPlatform);
            if (exact != null && exact.samples > 0) {
                return OptionalDouble.of(exact.mean);
            }
        }
        Value any = shared.get(fromStation + ">" + toStation);
        return any == null || any.samples == 0 ? OptionalDouble.empty() : OptionalDouble.of(any.mean);
    }

    /** Seconds between departures of the service at stop {@code index}, as learned. */
    public OptionalDouble intervalSeconds(Service service, int index) {
        ServiceTimes times = current(service);
        return times == null ? OptionalDouble.empty() : times.interval.get(index);
    }

    /** The usual spread (mean absolute deviation) of the segment's travel time, when learned from a few runs. */
    public OptionalDouble segmentSpread(Service service, int fromIndex) {
        ServiceTimes times = current(service);
        return times == null ? OptionalDouble.empty() : times.segment.spread(fromIndex);
    }

    private void recordPlatform(Network network, ServiceKey key, int index, String platformId) {
        times(network, key).ifPresent(times -> {
            if (index < times.platforms.length && !platformId.equals(times.platforms[index])) {
                times.platforms[index] = platformId;
                dirty = true;
            }
        });
    }

    /**
     * Seconds from leaving stop {@code fromIndex} until reaching the next stop: learned from this
     * service's own trains, else from any service on the same stretch of track.
     */
    public OptionalDouble segmentSeconds(Service service, int fromIndex) {
        ServiceTimes times = current(service);
        OptionalDouble own = times == null ? OptionalDouble.empty() : times.segment.get(fromIndex);
        return own.isPresent() ? own : sharedSegmentSeconds(service, fromIndex);
    }

    /** Seconds from leaving stop {@code fromIndex} until the next stop, from this service's own trains only. */
    public OptionalDouble ownSegmentSeconds(Service service, int fromIndex) {
        ServiceTimes times = current(service);
        return times == null ? OptionalDouble.empty() : times.segment.get(fromIndex);
    }

    /** Seconds trains usually stand at stop {@code index}. */
    public OptionalDouble dwellSeconds(Service service, int index) {
        ServiceTimes times = current(service);
        return times == null ? OptionalDouble.empty() : times.dwell.get(index);
    }

    /** The platform trains of this service were last seen using at stop {@code index}. */
    public Optional<String> usualPlatform(Service service, int index) {
        ServiceTimes times = current(service);
        if (times == null || index < 0 || index >= times.platforms.length) {
            return Optional.empty();
        }
        return Optional.ofNullable(times.platforms[index]);
    }

    /** Number of segments of the service with a learned travel time. */
    public int learnedSegments(Service service) {
        ServiceTimes times = current(service);
        return times == null ? 0 : times.segment.knownCount();
    }

    public void forget(ServiceKey key) {
        if (services.remove(key) != null) {
            dirty = true;
        }
        lastDepartures.remove(key);
    }

    /**
     * Carries learned data across an edit of a service's stops: every stop still present keeps its
     * dwell time and platform, and a segment keeps its travel time when the stop after it is still
     * the same. Without this, adding one station would throw away a service's whole history.
     */
    public void remap(Service before, Service after) {
        ServiceTimes old = services.get(before.key());
        if (old == null || !old.fingerprint.equals(before.fingerprint()) || old.size != before.size()) {
            return;
        }
        int[] mapping = StopEdits.matchIndices(before.stops(), after.stops());
        ServiceTimes fresh = new ServiceTimes(after.fingerprint(), after.size());
        for (int index = 0; index < mapping.length; index++) {
            int source = mapping[index];
            if (source < 0) {
                continue;
            }
            fresh.dwell.copyFrom(old.dwell, source, index);
            fresh.interval.copyFrom(old.interval, source, index);
            fresh.platforms[index] = old.platforms[source];
            int nextSource = index + 1 < mapping.length ? mapping[index + 1]
                    : after.circular() && mapping.length > 0 ? mapping[0] : -1;
            if (nextSource >= 0 && nextSource == before.nextIndex(source)) {
                fresh.segment.copyFrom(old.segment, source, index);
            }
        }
        services.put(after.key(), fresh);
        dirty = true;
    }

    /** Whether data changed since the last {@link #save}. */
    public boolean dirty() {
        return dirty;
    }

    private Optional<ServiceTimes> times(Network network, ServiceKey key) {
        return network.service(key).map(service -> {
            ServiceTimes times = services.get(key);
            if (times == null || !times.fingerprint.equals(service.fingerprint()) || times.size != service.size()) {
                times = new ServiceTimes(service.fingerprint(), service.size());
                services.put(key, times);
            }
            return times;
        });
    }

    private @Nullable ServiceTimes current(Service service) {
        ServiceTimes times = services.get(service.key());
        if (times == null || !times.fingerprint.equals(service.fingerprint()) || times.size != service.size()) {
            return null;
        }
        return times;
    }

    public void save(ConfigurationSection root) {
        for (Map.Entry<ServiceKey, ServiceTimes> entry : services.entrySet()) {
            ServiceTimes times = entry.getValue();
            ConfigurationSection node = root.createSection(entry.getKey().toString());
            node.set("fingerprint", times.fingerprint);
            node.set("stops", times.size);
            node.set("segments", times.segment.serialize());
            node.set("dwell", times.dwell.serialize());
            if (times.interval.knownCount() > 0) {
                node.set("intervals", times.interval.serialize());
            }
            node.set("platforms", Arrays.stream(times.platforms).map(value -> value == null ? "" : value).toList());
        }
        if (!shared.isEmpty()) {
            ConfigurationSection node = root.createSection("shared");
            for (Map.Entry<String, Value> entry : shared.entrySet()) {
                node.set(entry.getKey().replace('.', '_'), entry.getValue().serialize());
            }
        }
        dirty = false;
    }

    public void load(ConfigurationSection root) {
        services.clear();
        shared.clear();
        for (String key : root.getKeys(false)) {
            ConfigurationSection node = root.getConfigurationSection(key);
            if (node == null) {
                continue;
            }
            if (key.equals("shared")) {
                for (String stretch : node.getKeys(false)) {
                    try {
                        Value value = new Value();
                        value.deserialize(node.getString(stretch, ""));
                        if (value.samples > 0) {
                            shared.put(stretch, value);
                        }
                    } catch (IllegalArgumentException ignored) {
                        // Relearned.
                    }
                }
                continue;
            }
            try {
                int size = node.getInt("stops");
                ServiceTimes times = new ServiceTimes(node.getString("fingerprint", ""), size);
                times.segment.deserialize(node.getStringList("segments"));
                times.dwell.deserialize(node.getStringList("dwell"));
                times.interval.deserialize(node.getStringList("intervals"));
                List<String> platforms = node.getStringList("platforms");
                for (int index = 0; index < Math.min(size, platforms.size()); index++) {
                    String value = platforms.get(index);
                    times.platforms[index] = value == null || value.isBlank() ? null : value;
                }
                services.put(ServiceKey.parse(key), times);
            } catch (IllegalArgumentException ignored) {
                // Learned data is disposable; a corrupt entry is simply relearned.
            }
        }
        dirty = false;
    }

    private final class ServiceTimes {
        private final String fingerprint;
        private final int size;
        private final Series segment;
        private final Series dwell;
        private final Series interval;
        private final String[] platforms;

        private ServiceTimes(String fingerprint, int size) {
            this.fingerprint = fingerprint;
            this.size = size;
            this.segment = new Series(size);
            this.dwell = new Series(size);
            this.interval = new Series(size);
            this.platforms = new String[size];
        }
    }

    /** One smoothed value with its sample count and spread. */
    private final class Value {
        private double mean;
        private int samples;
        private double spread;

        private void update(double seconds) {
            if (samples == 0) {
                mean = seconds;
                spread = 0;
            } else {
                boolean outlier = samples >= OUTLIER_MIN_SAMPLES
                        && (seconds > mean * OUTLIER_FACTOR || seconds < mean / OUTLIER_FACTOR);
                double weight = outlier ? smoothing / 5 : smoothing;
                double deviation = Math.abs(seconds - mean);
                mean += weight * (seconds - mean);
                spread += weight * (deviation - spread);
            }
            samples = Math.min(samples + 1, 1_000_000);
        }

        private String serialize() {
            return String.format(java.util.Locale.ROOT, "%.2f/%d/%.2f", mean, samples, spread);
        }

        private void deserialize(String entry) {
            String[] parts = entry.split("/");
            mean = Double.parseDouble(parts[0]);
            samples = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
            spread = parts.length > 2 ? Double.parseDouble(parts[2]) : 0;
        }
    }

    private final class Series {
        private final double[] values;
        private final int[] samples;
        /** Smoothed absolute deviation from the value, the usual spread. */
        private final double[] spreads;

        private Series(int size) {
            values = new double[size];
            samples = new int[size];
            spreads = new double[size];
        }

        private boolean update(int index, double seconds) {
            if (index < 0 || index >= values.length || !(seconds >= 0) || seconds > MAX_SAMPLE_SECONDS) {
                return false;
            }
            if (samples[index] == 0) {
                values[index] = seconds;
                spreads[index] = 0;
            } else {
                double average = values[index];
                boolean outlier = samples[index] >= OUTLIER_MIN_SAMPLES
                        && (seconds > average * OUTLIER_FACTOR || seconds < average / OUTLIER_FACTOR);
                double weight = outlier ? smoothing / 5 : smoothing;
                double deviation = Math.abs(seconds - average);
                values[index] = average + weight * (seconds - average);
                spreads[index] += weight * (deviation - spreads[index]);
            }
            samples[index] = Math.min(samples[index] + 1, 1_000_000);
            return true;
        }

        private OptionalDouble get(int index) {
            if (index < 0 || index >= values.length || samples[index] == 0) {
                return OptionalDouble.empty();
            }
            return OptionalDouble.of(values[index]);
        }

        /** The spread, once there are enough runs to know it. */
        private OptionalDouble spread(int index) {
            if (index < 0 || index >= values.length || samples[index] < OUTLIER_MIN_SAMPLES) {
                return OptionalDouble.empty();
            }
            return OptionalDouble.of(spreads[index]);
        }

        private void copyFrom(Series other, int from, int to) {
            values[to] = other.values[from];
            samples[to] = other.samples[from];
            spreads[to] = other.spreads[from];
        }

        private int knownCount() {
            int count = 0;
            for (int sample : samples) {
                if (sample > 0) {
                    count++;
                }
            }
            return count;
        }

        private List<String> serialize() {
            String[] result = new String[values.length];
            for (int index = 0; index < values.length; index++) {
                result[index] = samples[index] == 0 ? ""
                        : String.format(java.util.Locale.ROOT, "%.2f/%d/%.2f", values[index], samples[index], spreads[index]);
            }
            return List.of(result);
        }

        private void deserialize(List<String> entries) {
            for (int index = 0; index < Math.min(values.length, entries.size()); index++) {
                String entry = entries.get(index);
                if (entry == null || entry.isBlank()) {
                    continue;
                }
                String[] parts = entry.split("/");
                values[index] = Double.parseDouble(parts[0]);
                samples[index] = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
                spreads[index] = parts.length > 2 ? Double.parseDouble(parts[2]) : 0;
            }
        }
    }
}
