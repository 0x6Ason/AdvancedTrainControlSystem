package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Learns, per service, how long trains take between consecutive stops, how long they stand at
 * each stop, and which platform they use. Values are exponentially smoothed so they follow
 * timetable changes; isolated outliers (a train held at a signal) move them only slightly.
 *
 * <p>All data for a service is discarded when its stop list changes.
 */
public final class TravelTimes {
    static final double MAX_SAMPLE_SECONDS = Duration.ofHours(1).toSeconds();
    private static final int OUTLIER_MIN_SAMPLES = 3;
    private static final double OUTLIER_FACTOR = 3.0;

    private double smoothing;
    private final Map<ServiceKey, ServiceTimes> services = new HashMap<>();
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

    public void record(JourneyEvent event, Network network) {
        switch (event) {
            case JourneyEvent.Arrived arrived -> {
                recordSegment(network, arrived.service(), arrived.fromIndex(), arrived.travelTime());
                recordPlatform(network, arrived.service(), arrived.stopIndex(), arrived.platform().platformId());
            }
            case JourneyEvent.Passed passed when passed.stopIndex() >= 0 ->
                    recordSegment(network, passed.service(), passed.fromIndex(), passed.travelTime());
            case JourneyEvent.Departed departed when departed.dwell() != null ->
                    times(network, departed.service()).ifPresent(times -> {
                        if (times.dwell.update(departed.stopIndex(), departed.dwell().toMillis() / 1000.0)) {
                            dirty = true;
                        }
                    });
            default -> {
            }
        }
    }

    private void recordSegment(Network network, ServiceKey key, int fromIndex, @Nullable Duration travelTime) {
        if (fromIndex < 0 || travelTime == null) {
            return;
        }
        times(network, key).ifPresent(times -> {
            if (times.segment.update(fromIndex, travelTime.toMillis() / 1000.0)) {
                dirty = true;
            }
        });
    }

    private void recordPlatform(Network network, ServiceKey key, int index, String platformId) {
        times(network, key).ifPresent(times -> {
            if (index < times.platforms.length && !platformId.equals(times.platforms[index])) {
                times.platforms[index] = platformId;
                dirty = true;
            }
        });
    }

    /** Seconds from leaving stop {@code fromIndex} until reaching the next stop. */
    public OptionalDouble segmentSeconds(Service service, int fromIndex) {
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
            node.set("platforms", Arrays.stream(times.platforms).map(value -> value == null ? "" : value).toList());
        }
        dirty = false;
    }

    public void load(ConfigurationSection root) {
        services.clear();
        for (String key : root.getKeys(false)) {
            ConfigurationSection node = root.getConfigurationSection(key);
            if (node == null) {
                continue;
            }
            try {
                int size = node.getInt("stops");
                ServiceTimes times = new ServiceTimes(node.getString("fingerprint", ""), size);
                times.segment.deserialize(node.getStringList("segments"));
                times.dwell.deserialize(node.getStringList("dwell"));
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
        private final String[] platforms;

        private ServiceTimes(String fingerprint, int size) {
            this.fingerprint = fingerprint;
            this.size = size;
            this.segment = new Series(size);
            this.dwell = new Series(size);
            this.platforms = new String[size];
        }
    }

    private final class Series {
        private final double[] values;
        private final int[] samples;

        private Series(int size) {
            values = new double[size];
            samples = new int[size];
        }

        private boolean update(int index, double seconds) {
            if (index < 0 || index >= values.length || !(seconds >= 0) || seconds > MAX_SAMPLE_SECONDS) {
                return false;
            }
            if (samples[index] == 0) {
                values[index] = seconds;
            } else {
                double average = values[index];
                boolean outlier = samples[index] >= OUTLIER_MIN_SAMPLES
                        && (seconds > average * OUTLIER_FACTOR || seconds < average / OUTLIER_FACTOR);
                double weight = outlier ? smoothing / 5 : smoothing;
                values[index] = average + weight * (seconds - average);
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
                        : String.format(java.util.Locale.ROOT, "%.2f/%d", values[index], samples[index]);
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
            }
        }
    }
}
