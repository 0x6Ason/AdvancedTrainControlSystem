package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.ServiceKey;
import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Scores arrival estimates against what then happened. While a train runs towards a stop, the
 * estimate is sampled whenever its remaining time first drops under each horizon (ten minutes,
 * five, two, one, thirty seconds, fifteen); when the train arrives, the error of each sample is
 * added to the statistics of that service, stop and horizon. {@code /atcs timing report} reads
 * them, so a change to the estimator can be shown to help, and the stops that lie can be found
 * instead of guessed.
 */
public final class EtaSamples {
    /** Horizons in seconds at which an estimate is sampled, longest first. */
    public static final int[] HORIZONS = {600, 300, 120, 60, 30, 15};
    static final int MAX_WORST = 20;
    private static final Duration STALE = Duration.ofMinutes(30);

    /** Running error statistics of one service, stop and horizon; errors in seconds, positive when late. */
    public record Stat(int count, double sumAbs, double sumSigned, double maxAbs) {
        public static final Stat NONE = new Stat(0, 0, 0, 0);

        public double meanAbs() {
            return count == 0 ? 0 : sumAbs / count;
        }

        public double bias() {
            return count == 0 ? 0 : sumSigned / count;
        }

        Stat add(double error) {
            return new Stat(count + 1, sumAbs + Math.abs(error), sumSigned + error, Math.max(maxAbs, Math.abs(error)));
        }

        String serialize() {
            return String.format(Locale.ROOT, "%d/%.1f/%.1f/%.1f", count, sumAbs, sumSigned, maxAbs);
        }

        static Stat parse(String text) {
            String[] parts = text.split("/");
            return new Stat(Integer.parseInt(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]));
        }
    }

    /** One bad estimate kept for the report. */
    public record Worst(ServiceKey service, int stopIndex, int horizon, double error, Instant at) {
    }

    private record Key(ServiceKey service, int stopIndex, int horizon) {
    }

    private static final class Pending {
        private final ServiceKey service;
        private final Map<Integer, Instant> predictedArrival = new HashMap<>();
        private Instant lastTouched;

        private Pending(ServiceKey service, Instant now) {
            this.service = service;
            this.lastTouched = now;
        }
    }

    private final Map<Key, Stat> stats = new HashMap<>();
    private final List<Worst> worst = new ArrayList<>();
    /** Open samples per train and target stop index. */
    private final Map<String, Map<Integer, Pending>> pending = new HashMap<>();
    private boolean dirty;

    /** The estimate for a train's arrival at a stop, as shown to riders right now. */
    public void predicted(String trainId, ServiceKey service, int stopIndex, double seconds, Instant now) {
        Map<Integer, Pending> stops = pending.computeIfAbsent(trainId, ignored -> new HashMap<>());
        Pending open = stops.get(stopIndex);
        if (open == null || !open.service.equals(service)) {
            open = new Pending(service, now);
            stops.put(stopIndex, open);
        }
        open.lastTouched = now;
        for (int horizon : HORIZONS) {
            if (seconds <= horizon && !open.predictedArrival.containsKey(horizon)) {
                open.predictedArrival.put(horizon, now.plusMillis(Math.round(seconds * 1000)));
            }
        }
    }

    /** The train arrived; scores every sample taken for this stop. Returns the errors scored, by horizon. */
    public Map<Integer, Double> arrived(String trainId, ServiceKey service, int stopIndex, Instant at) {
        Map<Integer, Double> errors = new TreeMap<>();
        Map<Integer, Pending> stops = pending.get(trainId);
        if (stops == null) {
            return errors;
        }
        Pending open = stops.remove(stopIndex);
        stops.values().removeIf(other -> Duration.between(other.lastTouched, at).compareTo(STALE) > 0);
        if (stops.isEmpty()) {
            pending.remove(trainId);
        }
        if (open == null || !open.service.equals(service)) {
            return errors;
        }
        for (Map.Entry<Integer, Instant> sample : open.predictedArrival.entrySet()) {
            double error = Duration.between(sample.getValue(), at).toMillis() / 1000.0;
            errors.put(sample.getKey(), error);
            Key key = new Key(service, stopIndex, sample.getKey());
            stats.put(key, stats.getOrDefault(key, Stat.NONE).add(error));
            if (Math.abs(error) >= 10) {
                worst.add(new Worst(service, stopIndex, sample.getKey(), error, at));
                worst.sort(Comparator.comparingDouble((Worst entry) -> -Math.abs(entry.error())));
                while (worst.size() > MAX_WORST) {
                    worst.remove(worst.size() - 1);
                }
            }
            dirty = true;
        }
        return errors;
    }

    /** Drops open samples of a train (removed, renamed or re-assigned). */
    public void forget(String trainId) {
        pending.remove(trainId);
    }

    public void rename(String from, String to) {
        Map<Integer, Pending> stops = pending.remove(from);
        if (stops != null) {
            pending.put(to, stops);
        }
    }

    public Stat stat(ServiceKey service, int stopIndex, int horizon) {
        return stats.getOrDefault(new Key(service, stopIndex, horizon), Stat.NONE);
    }

    /** Statistics over every stop of the service, per horizon. */
    public Stat overall(ServiceKey service, int horizon) {
        Stat total = Stat.NONE;
        for (Map.Entry<Key, Stat> entry : stats.entrySet()) {
            if (entry.getKey().service().equals(service) && entry.getKey().horizon() == horizon) {
                Stat stat = entry.getValue();
                total = new Stat(total.count() + stat.count(), total.sumAbs() + stat.sumAbs(),
                        total.sumSigned() + stat.sumSigned(), Math.max(total.maxAbs(), stat.maxAbs()));
            }
        }
        return total;
    }

    /** Services with any scored sample, sorted. */
    public List<ServiceKey> services() {
        return stats.keySet().stream().map(Key::service).distinct().sorted().toList();
    }

    /** Stop indexes of the service with any scored sample, ascending. */
    public List<Integer> stops(ServiceKey service) {
        return stats.keySet().stream().filter(key -> key.service().equals(service)).map(Key::stopIndex).distinct().sorted().toList();
    }

    public List<Worst> worst(ServiceKey service) {
        return worst.stream().filter(entry -> service == null || entry.service().equals(service)).toList();
    }

    public int sampleCount() {
        int count = 0;
        for (Stat stat : stats.values()) {
            count += stat.count();
        }
        return count;
    }

    public void clear() {
        stats.clear();
        worst.clear();
        pending.clear();
        dirty = true;
    }

    public boolean dirty() {
        return dirty;
    }

    public void save(ConfigurationSection root) {
        Map<ServiceKey, List<String>> lines = new TreeMap<>();
        for (Map.Entry<Key, Stat> entry : stats.entrySet()) {
            Key key = entry.getKey();
            lines.computeIfAbsent(key.service(), ignored -> new ArrayList<>())
                    .add(key.stopIndex() + "@" + key.horizon() + "=" + entry.getValue().serialize());
        }
        for (Map.Entry<ServiceKey, List<String>> entry : lines.entrySet()) {
            entry.getValue().sort(Comparator.naturalOrder());
            root.set("stats." + entry.getKey(), entry.getValue());
        }
        List<String> worstLines = new ArrayList<>();
        for (Worst entry : worst) {
            worstLines.add(entry.service() + ";" + entry.stopIndex() + ";" + entry.horizon() + ";"
                    + String.format(Locale.ROOT, "%.1f", entry.error()) + ";" + entry.at());
        }
        root.set("worst", worstLines);
        dirty = false;
    }

    public void load(ConfigurationSection root) {
        stats.clear();
        worst.clear();
        ConfigurationSection section = root.getConfigurationSection("stats");
        if (section != null) {
            for (String service : section.getKeys(false)) {
                for (String line : section.getStringList(service)) {
                    try {
                        int at = line.indexOf('@');
                        int equals = line.indexOf('=');
                        Key key = new Key(ServiceKey.parse(service), Integer.parseInt(line.substring(0, at)),
                                Integer.parseInt(line.substring(at + 1, equals)));
                        stats.put(key, Stat.parse(line.substring(equals + 1)));
                    } catch (RuntimeException ignored) {
                        // Samples are disposable; a corrupt line is skipped.
                    }
                }
            }
        }
        for (String line : root.getStringList("worst")) {
            try {
                String[] parts = line.split(";");
                worst.add(new Worst(ServiceKey.parse(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                        Double.parseDouble(parts[3]), Instant.parse(parts[4])));
            } catch (RuntimeException ignored) {
                // Skipped as above.
            }
        }
        Iterator<Worst> iterator = worst.iterator();
        while (worst.size() > MAX_WORST && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
        dirty = false;
    }
}
