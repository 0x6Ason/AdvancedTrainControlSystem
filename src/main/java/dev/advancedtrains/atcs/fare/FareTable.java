package dev.advancedtrains.atcs.fare;

import dev.advancedtrains.atcs.network.Ids;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The fares of a railway, as configured in {@code fares.yml}: three layers, the highest that
 * applies wins.
 *
 * <ol>
 *   <li>an explicit fare for a pair of stations, in either direction;</li>
 *   <li>a fare for a pair of zones, each station being in at most one zone;</li>
 *   <li>a formula: a flat part, a part per stop travelled, a part per change of train, and
 *       distance bands over the track distance, rounded to a step and never under a minimum.</li>
 * </ol>
 *
 * @param currency the symbol shown with a fare, for example {@code $}
 * @param zones    station id to zone name
 * @param zoneFares zone pair key ({@link #zoneKey}) to fare
 * @param pairs    station pair key ({@link #pairKey}) to fare
 */
public record FareTable(String currency, Formula formula, Map<String, String> zones, Map<String, Double> zoneFares,
                        Map<String, Pair> pairs) {

    /** A distance band: the fare part for a track distance up to {@code upTo} blocks (0 or less: any distance). */
    public record Band(double upTo, double fare) {
        public boolean unbounded() {
            return upTo <= 0;
        }
    }

    /**
     * @param flat      charged on every journey
     * @param perStop   per calling stop travelled past
     * @param perChange per change of train
     * @param bands     distance bands, in ascending order of {@code upTo}; the first that fits applies
     * @param roundTo   the step fares are rounded to (0: none)
     * @param minimum   no journey costs less
     */
    public record Formula(double flat, double perStop, double perChange, List<Band> bands, double roundTo, double minimum) {
        public static final Formula NONE = new Formula(0, 0, 0, List.of(), 0, 0);

        public Formula {
            bands = List.copyOf(bands);
        }

        public boolean usesDistance() {
            return !bands.isEmpty();
        }

        /** Whether the formula charges anything at all. */
        public boolean charges() {
            return flat > 0 || perStop > 0 || perChange > 0 || bands.stream().anyMatch(band -> band.fare() > 0) || minimum > 0;
        }

        /** The band part for a track distance. */
        public double bandFare(double blocks) {
            for (Band band : bands) {
                if (band.unbounded() || blocks <= band.upTo()) {
                    return band.fare();
                }
            }
            return bands.isEmpty() ? 0 : bands.get(bands.size() - 1).fare();
        }

        public double finish(double fare) {
            double result = fare;
            if (roundTo > 0) {
                result = Math.round(result / roundTo) * roundTo;
            }
            return Math.max(result, minimum);
        }
    }

    /**
     * @param keep set by hand: {@code /atcs fare generate} never replaces it with the formula's value
     */
    public record Pair(double fare, boolean keep) {
    }

    public static final FareTable EMPTY = new FareTable("$", Formula.NONE, Map.of(), Map.of(), Map.of());

    public FareTable {
        currency = Objects.requireNonNullElse(currency, "$").strip();
        Objects.requireNonNull(formula, "formula");
        zones = Collections.unmodifiableMap(new LinkedHashMap<>(zones));
        zoneFares = Collections.unmodifiableMap(new LinkedHashMap<>(zoneFares));
        pairs = Collections.unmodifiableMap(new LinkedHashMap<>(pairs));
    }

    /** The key under which a fare between two stations is kept, whatever order they are given in. */
    public static String pairKey(String first, String second) {
        String a = Ids.normalize("station", first);
        String b = Ids.normalize("station", second);
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    public static String zoneKey(String first, String second) {
        String a = first.strip().toLowerCase(Locale.ROOT);
        String b = second.strip().toLowerCase(Locale.ROOT);
        return a.compareTo(b) <= 0 ? a + "-" + b : b + "-" + a;
    }

    public Optional<Pair> pair(String first, String second) {
        return Optional.ofNullable(pairs.get(pairKey(first, second)));
    }

    public Optional<String> zone(String stationId) {
        return Optional.ofNullable(zones.get(stationId));
    }

    /** The zone fare between two stations, when both are zoned and the zone pair has one. */
    public Optional<Double> zoneFare(String first, String second) {
        String a = zones.get(first);
        String b = zones.get(second);
        if (a == null || b == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(zoneFares.get(zoneKey(a, b)));
    }

    /** Whether anything is configured that would make a journey cost something. */
    public boolean configured() {
        return formula.charges() || !zoneFares.isEmpty() || !pairs.isEmpty();
    }

    public long keptPairs() {
        return pairs.values().stream().filter(Pair::keep).count();
    }

    public FareTable withCurrency(String newCurrency) {
        return new FareTable(newCurrency, formula, zones, zoneFares, pairs);
    }

    public FareTable withFormula(Formula newFormula) {
        return new FareTable(currency, newFormula, zones, zoneFares, pairs);
    }

    public FareTable withZone(String stationId, @Nullable String zone) {
        Map<String, String> updated = new LinkedHashMap<>(zones);
        if (zone == null) {
            updated.remove(stationId);
        } else {
            updated.put(stationId, zone.strip().toLowerCase(Locale.ROOT));
        }
        return new FareTable(currency, formula, updated, zoneFares, pairs);
    }

    public FareTable withZoneFare(String zoneA, String zoneB, @Nullable Double fare) {
        Map<String, Double> updated = new LinkedHashMap<>(zoneFares);
        if (fare == null) {
            updated.remove(zoneKey(zoneA, zoneB));
        } else {
            updated.put(zoneKey(zoneA, zoneB), fare);
        }
        return new FareTable(currency, formula, zones, updated, pairs);
    }

    public FareTable withPair(String first, String second, @Nullable Pair pair) {
        Map<String, Pair> updated = new LinkedHashMap<>(pairs);
        if (pair == null) {
            updated.remove(pairKey(first, second));
        } else {
            updated.put(pairKey(first, second), pair);
        }
        return new FareTable(currency, formula, zones, zoneFares, updated);
    }

    /** Replaces every pair at once, keeping the ones marked as set by hand unless the new map has them too. */
    public FareTable withPairs(Map<String, Pair> newPairs) {
        return new FareTable(currency, formula, zones, zoneFares, newPairs);
    }

    /** Pairs of station ids in a key, for messages. */
    public static List<String> stationsOf(String pairKey) {
        List<String> ids = new ArrayList<>();
        for (String id : pairKey.split("\\|")) {
            ids.add(id);
        }
        return ids;
    }

    /** A fare as text: no decimals when whole, two otherwise, with the currency in front. */
    public String format(double fare) {
        String amount = fare == Math.rint(fare) ? Long.toString(Math.round(fare)) : String.format(Locale.ROOT, "%.2f", fare);
        return currency.isEmpty() ? amount : currency + amount;
    }
}
