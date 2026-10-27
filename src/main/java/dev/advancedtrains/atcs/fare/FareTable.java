package dev.advancedtrains.atcs.fare;

import dev.advancedtrains.atcs.network.Ids;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
                        Map<String, Pair> pairs, Billing billing, Pricing pricing, String calculation) {

    /** Legacy tables contain total fares; cumulative tables contain station-hop supplements. */
    public enum Pricing { LEGACY, CUMULATIVE, HIGHEST, LOWEST, CUSTOM }

    public FareTable(String currency, Formula formula, Map<String, String> zones, Map<String, Double> zoneFares,
                     Map<String, Pair> pairs, Billing billing) {
        this(currency, formula, zones, zoneFares, pairs, billing, Pricing.LEGACY, FareExpression.ADDITIVE);
    }

    /** Automatic billing is opt-in; pricing rules also apply to fare previews when billing is disabled. */
    public record Billing(boolean enabled, Set<String> freeLines, double minimum, int transferWindowSeconds,
                          double transferDiscount, double transferDiscountPercent) {
        public static final Billing DEFAULT = new Billing(false, Set.of(), 0, 180, 0, 0);

        public Billing {
            Set<String> normalized = new LinkedHashSet<>();
            for (String line : freeLines) {
                normalized.add(Ids.normalize("line", line));
            }
            freeLines = Collections.unmodifiableSet(normalized);
            Checks.nonnegative("billing.minimum", minimum);
            Checks.nonnegative("billing.transfer-discount", transferDiscount);
            Checks.nonnegative("billing.transfer-discount-percent", transferDiscountPercent);
            if (transferDiscountPercent > 100) {
                throw new IllegalArgumentException("billing.transfer-discount-percent: must be between 0 and 100");
            }
            if (transferWindowSeconds < 0 || transferWindowSeconds > 3600) {
                throw new IllegalArgumentException("billing.transfer-window-seconds: must be between 0 and 3600");
            }
        }

        public boolean hasPricingRules() {
            return !freeLines.isEmpty() || minimum > 0 || transferDiscount > 0 || transferDiscountPercent > 0;
        }
    }

    /** A distance band: the fare part for a track distance up to {@code upTo} blocks (0 or less: any distance). */
    public record Band(double upTo, double fare) {
        public Band {
            if (!Double.isFinite(upTo)) {
                throw new IllegalArgumentException("distance band up-to: must be finite");
            }
            Checks.nonnegative("distance band fare", fare);
        }

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
            Checks.nonnegative("formula.flat", flat);
            Checks.nonnegative("formula.per-stop", perStop);
            Checks.nonnegative("formula.per-change", perChange);
            Checks.nonnegative("formula.round-to", roundTo);
            Checks.nonnegative("formula.minimum", minimum);
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

        /** Rounds without imposing a floor, so an observed journey can apply its minimum just once. */
        public double round(double fare) {
            Checks.nonnegative("calculated fare", fare);
            double steps = roundTo > 0 ? fare / roundTo : 0;
            // Beyond this range a double has no sub-step precision; Math.round would saturate a long.
            double result = roundTo > 0 && steps < Long.MAX_VALUE ? Math.round(steps) * roundTo : fare;
            Checks.nonnegative("rounded fare", result);
            return result;
        }

        public double finish(double fare) {
            return Math.max(round(fare), minimum);
        }
    }

    /**
     * @param keep set by hand: {@code /atcs fare generate} never replaces it with the formula's value
     */
    public record Pair(double fare, boolean keep) {
        public Pair {
            Checks.nonnegative("pair fare", fare);
        }
    }

    public static final FareTable EMPTY = new FareTable("$", Formula.NONE, Map.of(), Map.of(), Map.of());

    /** Source-compatible constructor for tables written before automatic billing existed. */
    public FareTable(String currency, Formula formula, Map<String, String> zones, Map<String, Double> zoneFares,
                     Map<String, Pair> pairs) {
        this(currency, formula, zones, zoneFares, pairs, Billing.DEFAULT);
    }

    public FareTable {
        currency = Objects.requireNonNullElse(currency, "$").strip();
        Objects.requireNonNull(formula, "formula");
        Objects.requireNonNull(billing, "billing");
        // Tariffs saved in older ledgers must retain their original pricing rules.
        pricing = Objects.requireNonNullElse(pricing, Pricing.LEGACY);
        calculation = Objects.requireNonNullElse(calculation, FareExpression.ADDITIVE);
        FareExpression.compile(calculation);
        zones = Collections.unmodifiableMap(new LinkedHashMap<>(zones));
        zoneFares = Collections.unmodifiableMap(new LinkedHashMap<>(zoneFares));
        pairs = Collections.unmodifiableMap(new LinkedHashMap<>(pairs));
        zoneFares.forEach((key, fare) -> Checks.nonnegative("zone-fares." + key, fare));
        pairs.values().forEach(pair -> Objects.requireNonNull(pair, "pair"));
    }

    static void nonnegative(String field, double value) {
        Checks.nonnegative(field, value);
    }

    /**
     * Kept apart so the nested records can validate without initialising {@link FareTable}, whose
     * {@link #EMPTY} needs {@link Formula#NONE} to exist first.
     */
    private static final class Checks {
        static void nonnegative(String field, double value) {
            if (!Double.isFinite(value) || value < 0) {
                throw new IllegalArgumentException(field + ": must be finite and nonnegative");
            }
        }
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
        return formula.charges() || !zoneFares.isEmpty() || !pairs.isEmpty() || billing.minimum() > 0;
    }

    public long keptPairs() {
        return pairs.values().stream().filter(Pair::keep).count();
    }

    public FareTable withPricing(Pricing newPricing) {
        return new FareTable(currency, formula, zones, zoneFares, pairs, billing, newPricing, calculation);
    }

    public FareTable withCalculation(String expression) {
        return new FareTable(currency, formula, zones, zoneFares, pairs, billing, Pricing.CUSTOM, expression);
    }

    public String expression() {
        return switch (pricing) {
            case HIGHEST -> "max(formula, zones, pairs)";
            case LOWEST -> "min(formula, zones, pairs)";
            case CUSTOM -> calculation;
            default -> FareExpression.ADDITIVE;
        };
    }

    public FareTable withCurrency(String newCurrency) {
        return new FareTable(newCurrency, formula, zones, zoneFares, pairs, billing, pricing, calculation);
    }

    public FareTable withFormula(Formula newFormula) {
        return new FareTable(currency, newFormula, zones, zoneFares, pairs, billing, pricing, calculation);
    }

    public FareTable withBilling(Billing newBilling) {
        return new FareTable(currency, formula, zones, zoneFares, pairs, newBilling, pricing, calculation);
    }

    public FareTable withZone(String stationId, @Nullable String zone) {
        Map<String, String> updated = new LinkedHashMap<>(zones);
        if (zone == null) {
            updated.remove(stationId);
        } else {
            updated.put(stationId, zone.strip().toLowerCase(Locale.ROOT));
        }
        return new FareTable(currency, formula, updated, zoneFares, pairs, billing, pricing, calculation);
    }

    public FareTable withZoneFare(String zoneA, String zoneB, @Nullable Double fare) {
        Map<String, Double> updated = new LinkedHashMap<>(zoneFares);
        if (fare == null) {
            updated.remove(zoneKey(zoneA, zoneB));
        } else {
            updated.put(zoneKey(zoneA, zoneB), fare);
        }
        return new FareTable(currency, formula, zones, updated, pairs, billing, pricing, calculation);
    }

    public FareTable withPair(String first, String second, @Nullable Pair pair) {
        Map<String, Pair> updated = new LinkedHashMap<>(pairs);
        if (pair == null) {
            updated.remove(pairKey(first, second));
        } else {
            updated.put(pairKey(first, second), pair);
        }
        return new FareTable(currency, formula, zones, zoneFares, updated, billing, pricing, calculation);
    }

    /** Replaces every pair at once, keeping the ones marked as set by hand unless the new map has them too. */
    public FareTable withPairs(Map<String, Pair> newPairs) {
        return new FareTable(currency, formula, zones, zoneFares, newPairs, billing, pricing, calculation);
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
