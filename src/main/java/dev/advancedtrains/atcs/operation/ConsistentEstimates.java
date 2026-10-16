package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Estimates for every train of a service together, in running order: a train cannot reach a
 * platform before the train in front of it has left that platform and a separation has passed.
 * Where the plain estimate of a following train would break that, its arrival at that stop and at
 * every stop after it is pushed back. This is what keeps estimates honest on shared track and on
 * bunched loop lines. Built once per refresh over all tracked journeys.
 */
public final class ConsistentEstimates implements Estimator {
    /** Seconds between one train leaving a platform and the next arriving at it. */
    public static final double SEPARATION_SECONDS = 5;
    /** How many stops ahead of each train are projected. */
    static final int LOOKAHEAD = 12;

    private final EtaCalculator plain;
    /** Projected seconds until arrival, per train and absolute stop index, after the ordering pass. */
    private final Map<String, Map<Integer, Double>> projected = new HashMap<>();

    public ConsistentEstimates(EtaCalculator plain) {
        this.plain = plain;
    }

    /** Builds the projection for all journeys at {@code now}. */
    public static ConsistentEstimates compute(Collection<JourneySnapshot> journeys, Network network, EtaCalculator plain,
                                              Instant now) {
        ConsistentEstimates estimates = new ConsistentEstimates(plain);
        Map<ServiceKey, List<JourneySnapshot>> byService = new HashMap<>();
        for (JourneySnapshot journey : journeys) {
            if (journey.service() != null && journey.phase() != JourneyPhase.COMPLETED && journey.phase() != JourneyPhase.IDLE) {
                byService.computeIfAbsent(journey.service(), ignored -> new ArrayList<>()).add(journey);
            }
        }
        for (Map.Entry<ServiceKey, List<JourneySnapshot>> entry : byService.entrySet()) {
            Service service = network.service(entry.getKey()).orElse(null);
            if (service != null) {
                estimates.project(service, entry.getValue(), now);
            }
        }
        return estimates;
    }

    private void project(Service service, List<JourneySnapshot> journeys, Instant now) {
        // Each train's own projection: seconds to each of its next stops, and the dwell there.
        record Train(JourneySnapshot journey, double position, List<Integer> stops, Map<Integer, Double> arrival) {
        }
        List<Train> trains = new ArrayList<>();
        for (JourneySnapshot journey : journeys) {
            Map<Integer, Double> arrival = new HashMap<>();
            List<Integer> stops = new ArrayList<>();
            int index = journey.phase() == JourneyPhase.AT_STOP || journey.phase() == JourneyPhase.PASSING
                    ? journey.lastIndex() : service.nextIndex(journey.lastIndex());
            if (journey.phase() == JourneyPhase.AT_STOP && journey.lastIndex() >= 0) {
                arrival.put(journey.lastIndex(), 0.0);
                stops.add(journey.lastIndex());
                index = service.nextIndex(journey.lastIndex());
            } else if (journey.phase() == JourneyPhase.PASSING) {
                index = service.nextIndex(journey.lastIndex());
            }
            for (int step = 0; step < LOOKAHEAD && index >= 0; step++) {
                OptionalDouble seconds = plain.secondsUntil(journey, service, index, now);
                if (seconds.isEmpty()) {
                    break;
                }
                arrival.put(index, seconds.getAsDouble());
                stops.add(index);
                index = service.nextIndex(index);
            }
            trains.add(new Train(journey, position(journey, service, now), stops, arrival));
        }
        trains.sort(Comparator.comparingDouble(Train::position).reversed());
        // From the front of the line backwards: each train keeps clear of the one in front.
        for (int behind = 1; behind < trains.size(); behind++) {
            Train ahead = trains.get(behind - 1);
            Train train = trains.get(behind);
            double shift = 0;
            for (int stop : train.stops()) {
                Double mine = train.arrival().get(stop);
                Double theirs = ahead.arrival().get(stop);
                if (mine == null) {
                    continue;
                }
                mine += shift;
                if (theirs != null && mine < theirs + aheadDwell(ahead.journey(), service, stop, now) + SEPARATION_SECONDS) {
                    double pushed = theirs + aheadDwell(ahead.journey(), service, stop, now) + SEPARATION_SECONDS;
                    shift += pushed - mine;
                    mine = pushed;
                }
                train.arrival().put(stop, mine);
            }
        }
        for (Train train : trains) {
            projected.put(train.journey().trainId(), train.arrival());
        }
    }

    /** How long the train in front stays at the stop: what is left of its stand when it is there now, else the usual dwell. */
    private double aheadDwell(JourneySnapshot ahead, Service service, int stop, Instant now) {
        if (ahead.phase() == JourneyPhase.AT_STOP && ahead.lastIndex() == stop) {
            return plain.remainingAtStop(ahead, service, stop, now);
        }
        return plain.dwell(service, stop);
    }

    /** Where the train is along the service, in stops, for ordering: index plus the share of the segment done. */
    static double position(JourneySnapshot journey, Service service, Instant now) {
        return switch (journey.phase()) {
            case AT_STOP, PASSING -> journey.lastIndex();
            case IN_TRANSIT -> journey.lastIndex() + 0.5;
            default -> -1;
        };
    }

    @Override
    public OptionalDouble secondsUntil(JourneySnapshot journey, Service service, int target, Instant now) {
        Map<Integer, Double> arrival = projected.get(journey.trainId());
        if (arrival != null && journey.service() != null && journey.service().equals(service.key())) {
            Double seconds = arrival.get(target);
            if (seconds != null) {
                return OptionalDouble.of(seconds);
            }
        }
        return plain.secondsUntil(journey, service, target, now);
    }
}
