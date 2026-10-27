package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.Service;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Estimates when a train reaches a stop of its service from learned segment and dwell times,
 * corrected by what the {@link EtaContext} knows: a train's place on its segment (a slow or held
 * train stops counting down instead of sitting on "Arriving"), and the time ATCS itself will keep
 * it at a platform. A segment nobody has learned yet is guessed from the {@link Priors} (track
 * distance at the railway's usual speed) and the estimate is marked approximate; a train that has
 * been on its segment longer than trains usually take is marked late.
 */
public final class EtaCalculator implements Estimator {
    /** A learned mean plus this many spreads is the time a train is usually there by. */
    static final double LATE_SPREADS = 1.6;
    /** The least a train may be over its usual time before it counts as late, in seconds. */
    static final double LATE_MARGIN_SECONDS = 10;

    /** Travel-time guesses for segments without learned data. */
    @FunctionalInterface
    public interface Priors {
        OptionalDouble segmentSeconds(Service service, int fromIndex);

        Priors NONE = (service, fromIndex) -> OptionalDouble.empty();
    }

    private final TravelTimes times;
    private final double defaultDwellSeconds;
    private final EtaContext context;
    private final Priors priors;
    /** Whether the context is being asked right now; see {@link #remainingAtStop}. */
    private boolean askingContext;

    public EtaCalculator(TravelTimes times, double defaultDwellSeconds) {
        this(times, defaultDwellSeconds, EtaContext.NONE, Priors.NONE);
    }

    public EtaCalculator(TravelTimes times, double defaultDwellSeconds, EtaContext context) {
        this(times, defaultDwellSeconds, context, Priors.NONE);
    }

    public EtaCalculator(TravelTimes times, double defaultDwellSeconds, EtaContext context, Priors priors) {
        this.times = times;
        this.defaultDwellSeconds = defaultDwellSeconds;
        this.context = context;
        this.priors = priors;
    }

    public EtaCalculator withContext(EtaContext newContext) {
        return new EtaCalculator(times, defaultDwellSeconds, newContext, priors);
    }

    /** Seconds trains stand at the stop: learned, else the configured default (0 for a run-through). */
    public double dwell(Service service, int index) {
        if (!service.stop(index).stops()) {
            return times.dwellSeconds(service, index).orElse(0);
        }
        return times.dwellSeconds(service, index).orElse(defaultDwellSeconds);
    }

    /** The learned time for a segment, else the prior, with whether it was a guess; empty when neither exists. */
    private Optional<Estimate> segment(Service service, int fromIndex) {
        OptionalDouble learned = times.segmentSeconds(service, fromIndex);
        if (learned.isPresent()) {
            return Optional.of(new Estimate(learned.getAsDouble(), false, false));
        }
        OptionalDouble prior = priors.segmentSeconds(service, fromIndex);
        return prior.isPresent() ? Optional.of(new Estimate(prior.getAsDouble(), true, false)) : Optional.empty();
    }

    @Override
    public Optional<Estimate> estimate(JourneySnapshot journey, Service service, int target, Instant now) {
        if (target < 0 || target >= service.size()) {
            return Optional.empty();
        }
        int last = journey.lastIndex();
        if (last >= service.size()) {
            return Optional.empty();
        }
        boolean onRouteVisit = journey.platform() != null && last >= 0
                && service.stop(last).stationId().equals(journey.platform().stationId());
        Estimate total;
        int cursor;
        switch (journey.phase()) {
            case AT_STOP, PASSING -> {
                if (onRouteVisit) {
                    if (last == target) {
                        return Optional.of(new Estimate(0, false, false));
                    }
                    double remainingDwell = 0;
                    if (journey.phase() == JourneyPhase.AT_STOP) {
                        remainingDwell = remainingAtStop(journey, service, last, now);
                    }
                    Optional<Estimate> segment = segment(service, last);
                    if (segment.isEmpty()) {
                        return Optional.empty();
                    }
                    total = segment.get().plus(remainingDwell, false);
                } else {
                    // Running through a station the service does not list: still on the segment from last.
                    Optional<Estimate> remaining = remainingSegment(journey, service, now);
                    if (remaining.isEmpty()) {
                        return Optional.empty();
                    }
                    total = remaining.get();
                }
                cursor = service.nextIndex(last);
            }
            case IN_TRANSIT -> {
                Optional<Estimate> remaining = remainingSegment(journey, service, now);
                if (remaining.isEmpty()) {
                    return Optional.empty();
                }
                total = remaining.get();
                cursor = service.nextIndex(last);
            }
            default -> {
                return Optional.empty();
            }
        }

        for (int step = 0; step <= service.size(); step++) {
            if (cursor < 0) {
                return Optional.empty();
            }
            if (cursor == target) {
                return Optional.of(total);
            }
            total = total.plus(dwell(service, cursor), false);
            Optional<Estimate> segment = segment(service, cursor);
            if (segment.isEmpty()) {
                return Optional.empty();
            }
            total = total.plus(segment.get().seconds(), segment.get().approximate());
            cursor = service.nextIndex(cursor);
        }
        return Optional.empty();
    }

    /**
     * Seconds until the train leaves the stop it stands at: what ATCS decided, else learned dwell
     * minus the time stood. The context may ask the estimator back while deciding: how long a
     * train is held for even spacing depends on when the next one arrives, which on a circular
     * line depends on how long that one is held, and so on round the loop; a junction check asks
     * whether the train it is about is late. A question the context raises while it is answering
     * one is therefore settled by the plain dwell instead of being put to the context again.
     */
    double remainingAtStop(JourneySnapshot journey, Service service, int index, Instant now) {
        double plain = Math.max(0, dwell(service, index) - elapsed(journey.arrivedAt(), now));
        if (askingContext) {
            return plain;
        }
        askingContext = true;
        try {
            OptionalDouble controlled = context.remainingStop(journey.trainId(), now);
            return controlled.isPresent() ? Math.max(0, controlled.getAsDouble()) : plain;
        } finally {
            askingContext = false;
        }
    }

    /**
     * Seconds left on the segment the train is on: the learned time counted down by the clock
     * since departure, but never less than the share of the recorded path still ahead says. A
     * train that is slow, held or standing therefore stops counting down instead of sitting on
     * "Arriving"; a train on time is unaffected. Once the train has been on its way longer than
     * trains usually take, by more than the usual spread, the estimate is marked late.
     */
    private Optional<Estimate> remainingSegment(JourneySnapshot journey, Service service, Instant now) {
        if (journey.lastIndex() < 0 || journey.segmentStart() == null) {
            return Optional.empty();
        }
        Optional<Estimate> segment = segment(service, journey.lastIndex());
        if (segment.isEmpty()) {
            return Optional.empty();
        }
        double usual = segment.get().seconds();
        double gone = elapsed(journey.segmentStart(), now);
        double byClock = Math.max(0, usual - gone);
        boolean late = !segment.get().approximate() && isLate(service, journey.lastIndex(), usual, gone);
        OptionalDouble progress = context.segmentProgress(journey.trainId(), service, journey.lastIndex());
        if (progress.isPresent()) {
            double byPath = usual * (1 - Math.max(0, Math.min(1, progress.getAsDouble())));
            return Optional.of(new Estimate(Math.max(byClock, byPath), segment.get().approximate(), late));
        }
        if (context.standing(journey.trainId())) {
            // No path to measure against: freeze at a floor instead of counting into "Arriving".
            return Optional.of(new Estimate(Math.max(byClock, 5), segment.get().approximate(), late));
        }
        return Optional.of(new Estimate(byClock, segment.get().approximate(), late));
    }

    /** Running order within a segment: recorded position first, elapsed travel time as a fallback. */
    double segmentProgress(JourneySnapshot journey, Service service, Instant now) {
        OptionalDouble progress = context.segmentProgress(journey.trainId(), service, journey.lastIndex());
        if (progress.isPresent()) {
            return Math.max(0, Math.min(1, progress.getAsDouble()));
        }
        Optional<Estimate> usual = segment(service, journey.lastIndex());
        if (journey.segmentStart() != null && usual.isPresent() && usual.get().seconds() > 0) {
            return Math.max(0, Math.min(1, elapsed(journey.segmentStart(), now) / usual.get().seconds()));
        }
        return 0.5;
    }

    /** Whether {@code gone} seconds on the segment is past the time trains are usually there by. */
    private boolean isLate(Service service, int fromIndex, double usual, double gone) {
        OptionalDouble spread = times.segmentSpread(service, fromIndex);
        if (spread.isEmpty()) {
            return false;
        }
        double allowance = Math.max(LATE_MARGIN_SECONDS, LATE_SPREADS * spread.getAsDouble());
        return gone > usual + allowance;
    }

    private static double elapsed(@Nullable Instant since, Instant now) {
        if (since == null) {
            return 0;
        }
        return Math.max(0, Duration.between(since, now).toMillis() / 1000.0);
    }
}
