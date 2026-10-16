package dev.advancedtrains.atcs.fare;

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
        Optional<FareTable.Pair> pair = table.pair(from, to);
        if (pair.isPresent()) {
            return Optional.of(new Quote(pair.get().fare(), Layer.PAIR, List.of("explicit fare for "
                    + network.stationName(from) + " and " + network.stationName(to)
                    + (pair.get().keep() ? " (set by hand)" : " (from the last apply)")), null));
        }
        Optional<Double> zoneFare = table.zoneFare(from, to);
        if (zoneFare.isPresent()) {
            return Optional.of(new Quote(zoneFare.get(), Layer.ZONE, List.of("zone fare " + table.zone(from).orElse("?")
                    + " to " + table.zone(to).orElse("?")), null));
        }
        FareTable.Formula formula = table.formula();
        Optional<RoutePlanner.Route> route = planner.route(from, to);
        if (route.isEmpty()) {
            return Optional.of(new Quote(formula.finish(formula.flat()), Layer.NONE,
                    List.of("no route between the stations; the flat fare applies"), null));
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
        return Optional.of(new Quote(finished, Layer.FORMULA, reasoning, found));
    }

    private String describe(RoutePlanner.Route route) {
        List<String> parts = new ArrayList<>();
        for (RoutePlanner.Leg leg : route.legs()) {
            parts.add((leg.walk() ? "walk" : String.valueOf(leg.service())) + " to " + network.stationName(leg.to()));
        }
        return String.join(", ", parts);
    }
}
