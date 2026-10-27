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
import java.util.Optional;
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
    /** Projected seconds until the next arrival, per train and service stop index, after the ordering pass. */
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
        record Visit(JourneySnapshot journey, int stop, double distance, double arrival) {
        }
        List<Visit> visits = new ArrayList<>();
        for (JourneySnapshot journey : journeys) {
            int last = journey.lastIndex();
            if (last < 0 || last >= service.size()) {
                continue;
            }
            boolean atCurrent = (journey.phase() == JourneyPhase.AT_STOP || journey.phase() == JourneyPhase.PASSING)
                    && journey.platform() != null && service.stop(last).stationId().equals(journey.platform().stationId());
            int index = atCurrent ? last : service.nextIndex(last);
            // In-transit trains are strictly between stops, even when path progress rounds to 0 or 1.
            double distance = atCurrent ? 0 : Math.max(0.000001,
                    1 - Math.max(0.000001, plain.segmentProgress(journey, service, now)));
            int count = Math.min(service.size(), LOOKAHEAD + (atCurrent ? 1 : 0));
            for (int step = 0; step < count && index >= 0; step++) {
                OptionalDouble seconds = plain.secondsUntil(journey, service, index, now);
                if (seconds.isEmpty()) {
                    break;
                }
                visits.add(new Visit(journey, index, distance + step, seconds.getAsDouble()));
                index = service.nextIndex(index);
            }
        }
        // Each stop is visited at most once per train. Distance forward from the train orders both
        // its own visits and the trains approaching each platform, including across a loop's seam.
        // Thus a future lap cannot delay a nearer visit, and delays only flow forwards, never round
        // a cycle back into the same arrival. The time is just a tie-breaker, not the running order.
        visits.sort(Comparator.comparingDouble(Visit::distance).thenComparingDouble(Visit::arrival)
                .thenComparing(visit -> visit.journey().trainId()));
        Map<String, Double> shifts = new HashMap<>();
        Map<Integer, Double> clearAt = new HashMap<>();
        for (Visit visit : visits) {
            String train = visit.journey().trainId();
            double shift = shifts.getOrDefault(train, 0.0);
            double arrival = visit.arrival() + shift;
            // An observed arrival is a fact, even if two trains currently occupy the same platform.
            if (visit.distance() > 0) {
                double pushed = Math.max(arrival, clearAt.getOrDefault(visit.stop(), 0.0));
                shifts.put(train, shift + pushed - arrival);
                arrival = pushed;
            }
            projected.computeIfAbsent(train, ignored -> new HashMap<>()).put(visit.stop(), arrival);
            double clear = arrival + aheadDwell(visit.journey(), service, visit.stop(), now) + SEPARATION_SECONDS;
            clearAt.merge(visit.stop(), clear, Math::max);
        }
    }

    /** How long the train in front stays at the stop: what is left of its stand when it is there now, else the usual dwell. */
    private double aheadDwell(JourneySnapshot ahead, Service service, int stop, Instant now) {
        if (ahead.phase() == JourneyPhase.AT_STOP && ahead.lastIndex() == stop) {
            return plain.remainingAtStop(ahead, service, stop, now);
        }
        return plain.dwell(service, stop);
    }

    @Override
    public Optional<Estimate> estimate(JourneySnapshot journey, Service service, int target, Instant now) {
        Optional<Estimate> own = plain.estimate(journey, service, target, now);
        Map<Integer, Double> arrival = projected.get(journey.trainId());
        if (arrival != null && journey.service() != null && journey.service().equals(service.key())) {
            Double seconds = arrival.get(target);
            if (seconds != null) {
                // The projection moves the time; whether it is a guess or overdue is the train's own.
                return Optional.of(own.map(estimate -> estimate.withSeconds(seconds))
                        .orElse(new Estimate(seconds, false, false)));
            }
        }
        return own;
    }
}
