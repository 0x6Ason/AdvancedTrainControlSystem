package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalDouble;

/**
 * Estimates when a train reaches a stop of its service from learned segment and dwell times.
 */
public final class EtaCalculator {
    private final TravelTimes times;
    private final double defaultDwellSeconds;

    public EtaCalculator(TravelTimes times, double defaultDwellSeconds) {
        this.times = times;
        this.defaultDwellSeconds = defaultDwellSeconds;
    }

    /**
     * Seconds until the train reaches stop {@code target}: zero while it stands there, empty when
     * the journey state or the learned timings are insufficient.
     */
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
                        double dwell = times.dwellSeconds(service, last).orElse(defaultDwellSeconds);
                        remainingDwell = Math.max(0, dwell - elapsed(journey.arrivedAt(), now));
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
            if (service.stop(cursor).stops()) {
                total += times.dwellSeconds(service, cursor).orElse(defaultDwellSeconds);
            } else {
                total += times.dwellSeconds(service, cursor).orElse(0);
            }
            OptionalDouble segment = times.segmentSeconds(service, cursor);
            if (segment.isEmpty()) {
                return OptionalDouble.empty();
            }
            total += segment.getAsDouble();
            cursor = service.nextIndex(cursor);
        }
        return OptionalDouble.empty();
    }

    private OptionalDouble remainingSegment(JourneySnapshot journey, Service service, Instant now) {
        if (journey.lastIndex() < 0 || journey.segmentStart() == null) {
            return OptionalDouble.empty();
        }
        OptionalDouble segment = times.segmentSeconds(service, journey.lastIndex());
        if (segment.isEmpty()) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(Math.max(0, segment.getAsDouble() - elapsed(journey.segmentStart(), now)));
    }

    private static double elapsed(Instant since, Instant now) {
        if (since == null) {
            return 0;
        }
        return Math.max(0, Duration.between(since, now).toMillis() / 1000.0);
    }
}
