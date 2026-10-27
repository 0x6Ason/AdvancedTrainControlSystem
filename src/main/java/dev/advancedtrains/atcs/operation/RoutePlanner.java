package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.network.Transfer;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.PriorityQueue;
import java.util.function.ToIntFunction;

/**
 * The quickest way between two stations over the services of the network: learned travel and
 * stop times where known, defaults where not, a penalty per change of train, and walking
 * transfers. This is the route fares are worked out on; the website's planner, which knows the
 * trains running right now, keeps its own.
 */
public final class RoutePlanner {

    /**
     * One ride (or walk) of a route.
     *
     * @param service  the service ridden, or null for a walk
     * @param stops    calling stops travelled past, the one alighted at included
     * @param blocks   track distance when known, else 0 (see {@link Route#distanceKnown})
     */
    public record Leg(@Nullable ServiceKey service, String from, String to, int stops, double seconds, double blocks,
                      List<String> path) {
        public Leg(@Nullable ServiceKey service, String from, String to, int stops, double seconds, double blocks) {
            this(service, from, to, stops, seconds, blocks, List.of(from, to));
        }

        public Leg {
            path = List.copyOf(path);
        }

        public boolean walk() {
            return service == null;
        }
    }

    /**
     * @param changes       trains boarded after the first
     * @param stops         calling stops travelled past, over all rides
     * @param blocks        track distance over all rides, where known
     * @param distanceKnown whether every ride had a measured distance
     */
    public record Route(List<Leg> legs, double seconds, int changes, int stops, double blocks, boolean distanceKnown) {
        public Route {
            legs = List.copyOf(legs);
        }
    }

    private final Network network;
    private final TravelTimes times;
    private final RouteMeasure measure;
    private final double defaultSegmentSeconds;
    private final double defaultDwellSeconds;
    private final double changePenaltySeconds;
    private final ToIntFunction<Transfer> walkSeconds;

    public RoutePlanner(Network network, TravelTimes times, RouteMeasure measure, double defaultSegmentSeconds,
                        double defaultDwellSeconds, double changePenaltySeconds, ToIntFunction<Transfer> walkSeconds) {
        this.network = network;
        this.times = times;
        this.measure = measure;
        this.defaultSegmentSeconds = defaultSegmentSeconds;
        this.defaultDwellSeconds = defaultDwellSeconds;
        this.changePenaltySeconds = changePenaltySeconds;
        this.walkSeconds = walkSeconds;
    }

    /** A planner with the usual defaults: a minute per unknown segment, ten seconds a stop, a minute per change. */
    public static RoutePlanner of(Network network, TravelTimes times, RouteMeasure measure) {
        return new RoutePlanner(network, times, measure, 60, 10, 60, transfer -> transfer.walk() == null ? 120 : transfer.walk());
    }

    private record State(String station, @Nullable ServiceKey service, int index) {
    }

    private record Step(State from, Leg leg) {
    }

    /** The quickest route, or empty when the stations are unknown or not connected. */
    public Optional<Route> route(String fromId, String toId) {
        if (network.station(fromId).isEmpty() || network.station(toId).isEmpty()) {
            return Optional.empty();
        }
        if (fromId.equals(toId)) {
            return Optional.of(new Route(List.of(), 0, 0, 0, 0, true));
        }
        Map<State, Double> best = new HashMap<>();
        Map<State, Step> steps = new HashMap<>();
        PriorityQueue<Map.Entry<State, Double>> open = new PriorityQueue<>(Map.Entry.comparingByValue());
        State start = new State(fromId, null, -1);
        best.put(start, 0.0);
        open.add(Map.entry(start, 0.0));
        State arrival = null;
        while (!open.isEmpty()) {
            Map.Entry<State, Double> current = open.poll();
            State state = current.getKey();
            double cost = current.getValue();
            if (cost > best.getOrDefault(state, Double.MAX_VALUE)) {
                continue;
            }
            if (state.station().equals(toId)) {
                arrival = state;
                break;
            }
            // Board any service calling here (a change unless it is the one we arrived on).
            for (Service service : network.servicesListing(state.station())) {
                for (int index = 0; index < service.size(); index++) {
                    if (!service.stop(index).stops() || !service.stop(index).stationId().equals(state.station())) {
                        continue;
                    }
                    boolean same = service.key().equals(state.service()) && index == state.index();
                    boolean continuation = state.service() != null && state.index() >= 0 && !same
                            && continuationOf(state.service(), state.index(), service.key());
                    double penalty = state.service() == null || same || continuation ? 0 : changePenaltySeconds;
                    // Ride to the next calling stop (through pass stops), one hop at a time.
                    int next = service.nextCallingIndex(index);
                    if (next < 0) {
                        continue;
                    }
                    double seconds = 0;
                    double blocks = 0;
                    boolean measured = true;
                    List<String> path = new ArrayList<>();
                    path.add(state.station());
                    for (int cursor = index; cursor != next; cursor = service.nextIndex(cursor)) {
                        seconds += times.segmentSeconds(service, cursor).orElse(defaultSegmentSeconds);
                        if (cursor != index) {
                            seconds += times.dwellSeconds(service, cursor).orElse(0);
                        }
                        path.add(service.stop(service.nextIndex(cursor)).stationId());
                        OptionalDouble length = measure.segmentBlocks(service, cursor);
                        if (length.isPresent()) {
                            blocks += length.getAsDouble();
                        } else {
                            measured = false;
                        }
                    }
                    if (!same && state.service() != null) {
                        seconds += times.dwellSeconds(service, index).orElse(defaultDwellSeconds);
                    }
                    State to = new State(service.stop(next).stationId(), service.key(), next);
                    double total = cost + penalty + seconds;
                    if (total < best.getOrDefault(to, Double.MAX_VALUE)) {
                        best.put(to, total);
                        steps.put(to, new Step(state, new Leg(service.key(), state.station(), to.station(), 1, seconds, measured ? blocks : -1, path)));
                        open.add(Map.entry(to, total));
                    }
                }
            }
            // Walk to a nearby station.
            for (Transfer transfer : network.transfersAt(state.station())) {
                String other = transfer.other(state.station());
                int seconds = walkSeconds.applyAsInt(transfer);
                State to = new State(other, null, -1);
                double total = cost + seconds;
                if (total < best.getOrDefault(to, Double.MAX_VALUE)) {
                    best.put(to, total);
                    steps.put(to, new Step(state, new Leg(null, state.station(), other, 0, seconds, 0)));
                    open.add(Map.entry(to, total));
                }
            }
        }
        if (arrival == null) {
            return Optional.empty();
        }
        List<Leg> hops = new ArrayList<>();
        State cursor = arrival;
        while (steps.containsKey(cursor)) {
            Step step = steps.get(cursor);
            hops.add(0, step.leg());
            cursor = step.from();
        }
        return Optional.of(merge(hops, best.get(arrival)));
    }

    private boolean continuationOf(ServiceKey from, int index, ServiceKey to) {
        Service service = network.service(from).orElse(null);
        return service != null && service.isTerminus(index) && to.equals(service.continuesAs());
    }

    /** Joins consecutive hops on the same service into one leg and sums the totals. */
    private static Route merge(List<Leg> hops, double seconds) {
        List<Leg> legs = new ArrayList<>();
        boolean distanceKnown = true;
        int stops = 0;
        double blocks = 0;
        for (Leg hop : hops) {
            boolean unmeasured = !hop.walk() && hop.blocks() < 0;
            if (unmeasured) {
                distanceKnown = false;
            }
            double hopBlocks = unmeasured ? 0 : hop.blocks();
            if (!legs.isEmpty() && !hop.walk() && hop.service() != null && hop.service().equals(legs.get(legs.size() - 1).service())) {
                Leg last = legs.remove(legs.size() - 1);
                List<String> path = new ArrayList<>(last.path());
                path.addAll(hop.path().subList(1, hop.path().size()));
                legs.add(new Leg(last.service(), last.from(), hop.to(), last.stops() + hop.stops(),
                        last.seconds() + hop.seconds(), last.blocks() + hopBlocks, path));
            } else {
                legs.add(new Leg(hop.service(), hop.from(), hop.to(), hop.stops(), hop.seconds(), hopBlocks, hop.path()));
            }
            stops += hop.stops();
            blocks += hopBlocks;
        }
        int rides = (int) legs.stream().filter(leg -> !leg.walk()).count();
        return new Route(legs, seconds, Math.max(0, rides - 1), stops, blocks, distanceKnown);
    }
}
