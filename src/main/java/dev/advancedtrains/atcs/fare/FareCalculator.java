package dev.advancedtrains.atcs.fare;

import dev.advancedtrains.atcs.network.Ids;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.operation.RoutePlanner;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Works a fare out from the table and, for the formula, from the route the planner finds. */
public final class FareCalculator {

    public enum Layer { PAIR, ZONE, FORMULA, NONE }

    /** One observed ride, not a route inferred by the planner; stations need not differ for a loop ride. */
    public record RideLeg(String line, String from, String to, int stops, double blocks) {
        public RideLeg {
            line = Ids.normalize("line", line);
            from = Ids.normalize("station", from);
            to = Ids.normalize("station", to);
            if (stops < 0) {
                throw new IllegalArgumentException("ride stops: cannot be negative");
            }
            FareTable.nonnegative("ride blocks", blocks);
        }

        public boolean moved() {
            return stops > 0 || blocks > 0 || !from.equals(to);
        }
    }

    /**
     * @param reasoning why, in lines an operator can read
     * @param route     the route the formula assumed, when it was used
     */
    public record Quote(double fare, Layer layer, List<String> reasoning, RoutePlanner.@Nullable Route route) {
        public Quote {
            reasoning = List.copyOf(reasoning);
        }
    }

    private final FareTable table;
    private final Network network;
    private final RoutePlanner planner;

    public FareCalculator(FareTable table, Network network, RoutePlanner planner) {
        this.table = table;
        this.network = network;
        this.planner = planner;
    }

    public FareTable table() {
        return table;
    }

    public RoutePlanner planner() {
        return planner;
    }

    /** The fare between two stations, or empty when a station is unknown or the formula finds no route. */
    public Optional<Quote> quote(String from, String to) {
        if (network.station(from).isEmpty() || network.station(to).isEmpty()) {
            return Optional.empty();
        }
        if (from.equals(to)) {
            return Optional.of(new Quote(0, Layer.NONE, List.of("same station"), null));
        }
        if (table.billing().hasPricingRules()) {
            Optional<RoutePlanner.Route> planned = planner.route(from, to);
            if (planned.isPresent()) {
                RoutePlanner.Route route = planned.get();
                List<RideLeg> legs = route.legs().stream().filter(leg -> !leg.walk())
                        .map(leg -> new RideLeg(leg.service().lineId(), leg.from(), leg.to(), leg.stops(), leg.blocks()))
                        .toList();
                Quote quote = quoteRide(legs);
                List<String> reasoning = new ArrayList<>();
                reasoning.add("planned route: " + describe(route)
                        + (route.distanceKnown() ? "" : " (distance not fully recorded)"));
                reasoning.addAll(quote.reasoning());
                return Optional.of(new Quote(quote.fare(), quote.layer(), reasoning, route));
            }
        }
        Quote legacy = quoteLegacy(from, to);
        double fare = Math.max(legacy.fare(), table.billing().minimum());
        if (fare == legacy.fare()) {
            return Optional.of(legacy);
        }
        List<String> reasoning = new ArrayList<>(legacy.reasoning());
        reasoning.add("billing minimum " + table.format(fare));
        return Optional.of(new Quote(fare, legacy.layer(), reasoning, legacy.route()));
    }

    private Quote quoteLegacy(String from, String to) {
        Optional<FareTable.Pair> pair = table.pair(from, to);
        if (pair.isPresent()) {
            return new Quote(pair.get().fare(), Layer.PAIR, List.of("explicit fare for "
                    + network.stationName(from) + " and " + network.stationName(to)
                    + (pair.get().keep() ? " (set by hand)" : " (from the last apply)")), null);
        }
        Optional<Double> zoneFare = table.zoneFare(from, to);
        if (zoneFare.isPresent()) {
            return new Quote(zoneFare.get(), Layer.ZONE, List.of("zone fare " + table.zone(from).orElse("?")
                    + " to " + table.zone(to).orElse("?")), null);
        }
        FareTable.Formula formula = table.formula();
        Optional<RoutePlanner.Route> route = planner.route(from, to);
        if (route.isEmpty()) {
            return new Quote(formula.finish(formula.flat()), Layer.NONE,
                    List.of("no route between the stations; the flat fare applies"), null);
        }
        RoutePlanner.Route found = route.get();
        List<String> reasoning = new ArrayList<>();
        double fare = formula.flat();
        reasoning.add("route: " + describe(found) + " (" + found.stops() + " stops, " + found.changes() + " changes"
                + (found.distanceKnown() ? ", " + Math.round(found.blocks()) + " blocks" : ", distance not fully recorded") + ")");
        if (formula.flat() > 0) {
            reasoning.add("flat " + table.format(formula.flat()));
        }
        if (formula.perStop() > 0) {
            fare += formula.perStop() * found.stops();
            reasoning.add(found.stops() + " stops at " + table.format(formula.perStop()) + " = " + table.format(formula.perStop() * found.stops()));
        }
        if (formula.perChange() > 0 && found.changes() > 0) {
            fare += formula.perChange() * found.changes();
            reasoning.add(found.changes() + " changes at " + table.format(formula.perChange()) + " = " + table.format(formula.perChange() * found.changes()));
        }
        if (formula.usesDistance()) {
            double band = formula.bandFare(found.blocks());
            fare += band;
            reasoning.add("distance band for " + Math.round(found.blocks()) + " blocks: " + table.format(band)
                    + (found.distanceKnown() ? "" : " (part of the track is not recorded yet)"));
        }
        double finished = formula.finish(fare);
        if (finished != fare) {
            reasoning.add(String.format(Locale.ROOT, "%s rounded to %s", table.format(fare), table.format(finished)));
        }
        return new Quote(finished, Layer.FORMULA, reasoning, found);
    }

    /**
     * Prices an observed journey without consulting the planner or requiring its stations to still exist.
     * The caller groups legs into one journey using the transfer window; these legs carry no timestamps.
     * Continuous same-line rides merge before pair/zone/formula lookup. Each formula leg is rounded, but
     * its minimum is deferred until after all discounts. A different paid line gets
     * {@code max(0, legFare * (1 - percent / 100) - fixedDiscount)}. Free rides and stationary seat hops
     * never create transfer eligibility, and a hop that did not travel costs nothing by itself. Billing's
     * minimum, and the formula minimum when used, floor the paid journey once, including one that only
     * boarded and left a paid train at the same station; a journey containing only free lines costs zero.
     */
    public Quote quoteRide(List<RideLeg> legs) {
        FareTable.Billing billing = table.billing();
        FareTable.Formula formula = table.formula();
        List<String> reasoning = new ArrayList<>();
        String lastPaidLine = null;
        boolean stationaryPaid = legs.stream().anyMatch(leg -> !leg.moved() && !billing.freeLines().contains(leg.line()));
        boolean paidRide = false;
        boolean usedFormula = false;
        double total = 0;
        Layer layer = Layer.NONE;
        for (RideLeg leg : mergeRides(legs)) {
            String description = leg.line() + " " + leg.from() + " to " + leg.to();
            if (billing.freeLines().contains(leg.line())) {
                reasoning.add(description + ": free line");
                continue;
            }
            paidRide = true;
            boolean transfer = lastPaidLine != null && !lastPaidLine.equals(leg.line());
            Optional<FareTable.Pair> pair = table.pair(leg.from(), leg.to());
            Optional<Double> zone = table.zoneFare(leg.from(), leg.to());
            double fare;
            Layer legLayer;
            if (pair.isPresent()) {
                fare = pair.get().fare();
                legLayer = Layer.PAIR;
            } else if (zone.isPresent()) {
                fare = zone.get();
                legLayer = Layer.ZONE;
            } else {
                fare = formula.round(formula.flat() + formula.perStop() * leg.stops()
                        + formula.bandFare(leg.blocks()) + (transfer ? formula.perChange() : 0));
                legLayer = Layer.FORMULA;
                usedFormula = true;
            }
            reasoning.add(description + ": " + legLayer.name().toLowerCase(Locale.ROOT) + " " + table.format(fare)
                    + " (" + leg.stops() + " stops, " + Math.round(leg.blocks()) + " blocks)"
                    + (transfer && legLayer == Layer.FORMULA && formula.perChange() > 0
                    ? "; includes change " + table.format(formula.perChange()) : ""));
            if (transfer) {
                double discounted = Math.max(0, fare * (1 - billing.transferDiscountPercent() / 100) - billing.transferDiscount());
                if (discounted != fare) {
                    reasoning.add("transfer from " + lastPaidLine + ": " + table.format(fare) + " -> " + table.format(discounted));
                }
                fare = discounted;
            }
            total += fare;
            FareTable.nonnegative("journey fare", total);
            if (legLayer.ordinal() < layer.ordinal()) {
                layer = legLayer;
            }
            lastPaidLine = leg.line();
        }
        if (!paidRide && !stationaryPaid) {
            if (reasoning.isEmpty()) {
                reasoning.add("no paid ride");
            }
            return new Quote(0, Layer.NONE, reasoning, null);
        }
        if (!paidRide) {
            reasoning.add("boarded and left a paid train without travelling");
        }
        double minimum = Math.max(billing.minimum(), usedFormula || !paidRide ? formula.minimum() : 0);
        if (total < minimum) {
            reasoning.add("journey minimum " + table.format(minimum));
            total = minimum;
        }
        return new Quote(total, layer, reasoning, null);
    }

    private List<RideLeg> mergeRides(List<RideLeg> rides) {
        List<RideLeg> merged = new ArrayList<>();
        for (RideLeg leg : rides) {
            // A seat hop that went nowhere cannot split a continuous ride into two fares.
            if (!leg.moved()) {
                continue;
            }
            if (!merged.isEmpty()) {
                RideLeg last = merged.get(merged.size() - 1);
                if (last.line().equals(leg.line()) && last.to().equals(leg.from())) {
                    merged.set(merged.size() - 1, new RideLeg(last.line(), last.from(), leg.to(),
                            Math.addExact(last.stops(), leg.stops()), last.blocks() + leg.blocks()));
                    continue;
                }
            }
            merged.add(leg);
        }
        return merged;
    }

    private String describe(RoutePlanner.Route route) {
        List<String> parts = new ArrayList<>();
        for (RoutePlanner.Leg leg : route.legs()) {
            parts.add((leg.walk() ? "walk" : String.valueOf(leg.service())) + " to " + network.stationName(leg.to()));
        }
        return String.join(", ", parts);
    }
}
