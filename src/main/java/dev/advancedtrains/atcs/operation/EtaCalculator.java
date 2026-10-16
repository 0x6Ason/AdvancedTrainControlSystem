package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalDouble;

/**
 * Estimates when a train reaches a stop of its service from learned segment and dwell times,
 * corrected by what the {@link EtaContext} knows: a train's place on its segment (a slow or held
 * train stops counting down instead of sitting on "Arriving"), and the time ATCS itself will keep
 * it at a platform.
 */
public final class EtaCalculator implements Estimator {
    private final TravelTimes times;
    private final double defaultDwellSeconds;
    private final EtaContext context;

    public EtaCalculator(TravelTimes times, double defaultDwellSeconds) {
        this(times, defaultDwellSeconds, EtaContext.NONE);
    }

    public EtaCalculator(TravelTimes times, double defaultDwellSeconds, EtaContext context) {
        this.times = times;
        this.defaultDwellSeconds = defaultDwellSeconds;
        this.context = context;
    }

    public EtaCalculator withContext(EtaContext newContext) {
        return new EtaCalculator(times, defaultDwellSeconds, newContext);
    }

    /** Seconds trains stand at the stop: learned, else the configured default (0 for a run-through). */
    public double dwell(Service service, int index) {
        if (!service.stop(index).stops()) {
            return times.dwellSeconds(service, index).orElse(0);
        }
        return times.dwellSeconds(service, index).orElse(defaultDwellSeconds);
    }

    @Override
    public OptionalDouble secondsUntil(JourneySnapshot journey, Service service, int target, Instant now) {
        if (target < 0 || target >= service.size()) {
            return OptionalDouble.empty();
        }
        int last = journey.lastIndex();
        if (last >= service.size()) {
            return OptionalDouble.empty();
        }
        boolean onRouteVisit = journey.platform() != null && last >= 0
                && service.stop(last).stationId().equals(journey.platform().stationId());
        double total;
        int cursor;
        switch (journey.phase()) {
            case AT_STOP, PASSING -> {
                if (onRouteVisit) {
                    if (last == target) {
                        return OptionalDouble.of(0);
                    }
                    double remainingDwell = 0;
                    if (journey.phase() == JourneyPhase.AT_STOP) {
                        remainingDwell = remainingAtStop(journey, service, last, now);
                    }
                    OptionalDouble segment = times.segmentSeconds(service, last);
                    if (segment.isEmpty()) {
                        return OptionalDouble.empty();
                    }
                    total = remainingDwell + segment.getAsDouble();
                } else {
                    // Running through a station the service does not list: still on the segment from last.
                    OptionalDouble remaining = remainingSegment(journey, service, now);
                    if (remaining.isEmpty()) {
                        return OptionalDouble.empty();
                    }
                    total = remaining.getAsDouble();
                }
                cursor = service.nextIndex(last);
            }
            case IN_TRANSIT -> {
                OptionalDouble remaining = remainingSegment(journey, service, now);
                if (remaining.isEmpty()) {
                    return OptionalDouble.empty();
                }
                total = remaining.getAsDouble();
                cursor = service.nextIndex(last);
            }
            default -> {
                return OptionalDouble.empty();
            }
        }

        for (int step = 0; step <= service.size(); step++) {
            if (cursor < 0) {
                return OptionalDouble.empty();
            }
            if (cursor == target) {
                return OptionalDouble.of(total);
            }
            total += dwell(service, cursor);
            OptionalDouble segment = times.segmentSeconds(service, cursor);
            if (segment.isEmpty()) {
                return OptionalDouble.empty();
            }
            total += segment.getAsDouble();
            cursor = service.nextIndex(cursor);
        }
        return OptionalDouble.empty();
    }

    /** Seconds until the train leaves the stop it stands at: what ATCS decided, else learned dwell minus the time stood. */
    double remainingAtStop(JourneySnapshot journey, Service service, int index, Instant now) {
        OptionalDouble controlled = context.remainingStop(journey.trainId(), now);
        if (controlled.isPresent()) {
            return Math.max(0, controlled.getAsDouble());
        }
        return Math.max(0, dwell(service, index) - elapsed(journey.arrivedAt(), now));
    }

    /**
     * Seconds left on the segment the train is on: the learned time counted down by the clock
     * since departure, but never less than the share of the recorded path still ahead says. A
     * train that is slow, held or standing therefore stops counting down instead of sitting on
     * "Arriving"; a train on time is unaffected.
     */
    private OptionalDouble remainingSegment(JourneySnapshot journey, Service service, Instant now) {
        if (journey.lastIndex() < 0 || journey.segmentStart() == null) {
            return OptionalDouble.empty();
        }
        OptionalDouble segment = times.segmentSeconds(service, journey.lastIndex());
        if (segment.isEmpty()) {
            return OptionalDouble.empty();
        }
        double byClock = Math.max(0, segment.getAsDouble() - elapsed(journey.segmentStart(), now));
        OptionalDouble progress = context.segmentProgress(journey.trainId(), service, journey.lastIndex());
        if (progress.isPresent()) {
            double byPath = segment.getAsDouble() * (1 - Math.max(0, Math.min(1, progress.getAsDouble())));
            return OptionalDouble.of(Math.max(byClock, byPath));
        }
        if (context.standing(journey.trainId())) {
            // No path to measure against: freeze at a floor instead of counting into "Arriving".
            return OptionalDouble.of(Math.max(byClock, 5));
        }
        return OptionalDouble.of(byClock);
    }

    private static double elapsed(Instant since, Instant now) {
        if (since == null) {
            return 0;
        }
        return Math.max(0, Duration.between(since, now).toMillis() / 1000.0);
    }
}
