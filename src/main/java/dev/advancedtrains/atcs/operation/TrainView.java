package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Service;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Everything passenger displays need to describe a train on a service at one moment.
 *
 * @param nextIndex  the next calling stop (-1 when none)
 * @param etaNext    seconds until the next calling stop, when known
 * @param progress   share of the trip completed, 0 to 1
 * @param held        the train stands still between stations
 * @param holdReason  why ATCS keeps the train at its platform, or null
 * @param approximate the estimate is partly a guess from track distance
 * @param late        the train is overdue at its next stop
 */
public record TrainView(
        Line line,
        Service service,
        JourneySnapshot journey,
        int nextIndex,
        OptionalDouble etaNext,
        float progress,
        boolean held,
        @Nullable String holdReason,
        boolean approximate,
        boolean late) {

    public static Optional<TrainView> of(JourneySnapshot journey, Network network, TravelTimes times, Estimator eta, Instant now) {
        return of(journey, network, times, eta, EtaContext.NONE, now);
    }

    public static Optional<TrainView> of(
            JourneySnapshot journey,
            Network network,
            TravelTimes times,
            Estimator eta,
            EtaContext context,
            Instant now) {
        if (journey.service() == null) {
            return Optional.empty();
        }
        Service service = network.service(journey.service()).orElse(null);
        Line line = network.line(journey.service().lineId()).orElse(null);
        if (service == null || line == null || service.size() == 0) {
            return Optional.empty();
        }
        int next = journey.nextCallingIndex(service);
        Optional<Estimator.Estimate> estimate = next >= 0 && journey.phase() != JourneyPhase.AT_STOP
                ? eta.estimate(journey, service, next, now)
                : Optional.empty();
        OptionalDouble etaNext = estimate.map(value -> OptionalDouble.of(value.seconds())).orElse(OptionalDouble.empty());
        boolean held = journey.phase() == JourneyPhase.IN_TRANSIT && context.standing(journey.trainId());
        String reason = journey.phase() == JourneyPhase.AT_STOP ? context.holdReason(journey.trainId()) : null;
        return Optional.of(new TrainView(line, service, journey, next, etaNext,
                progress(journey, service, times, context, now), held, reason,
                estimate.map(Estimator.Estimate::approximate).orElse(false),
                estimate.map(Estimator.Estimate::late).orElse(false)));
    }

    private static float progress(JourneySnapshot journey, Service service, TravelTimes times, EtaContext context, Instant now) {
        int spans = service.circular() ? service.size() : service.size() - 1;
        if (spans <= 0) {
            return 0;
        }
        double position = switch (journey.phase()) {
            case IDLE -> 0;
            case COMPLETED -> spans;
            case AT_STOP, PASSING -> Math.max(0, journey.lastIndex());
            case IN_TRANSIT -> {
                double done = Math.max(0, journey.lastIndex());
                OptionalDouble onPath = context.segmentProgress(journey.trainId(), service, journey.lastIndex());
                OptionalDouble segment = times.segmentSeconds(service, journey.lastIndex());
                if (onPath.isPresent()) {
                    done += Math.min(0.95, Math.max(0, onPath.getAsDouble()));
                } else if (journey.segmentStart() != null && segment.isPresent() && segment.getAsDouble() > 0) {
                    double elapsed = Duration.between(journey.segmentStart(), now).toMillis() / 1000.0;
                    done += Math.min(0.95, Math.max(0, elapsed / segment.getAsDouble()));
                }
                yield done;
            }
        };
        return (float) Math.max(0, Math.min(1, position / spans));
    }

    /** The station the train is standing at or running through, if any. */
    public Optional<String> currentStationId() {
        return Optional.ofNullable(journey.currentStationId());
    }

    public boolean atStop() {
        return journey.phase() == JourneyPhase.AT_STOP && journey.platform() != null;
    }

    public boolean atTerminus() {
        return atStop() && journey.lastIndex() >= 0 && service.isTerminus(journey.lastIndex());
    }

    public boolean completed() {
        return journey.phase() == JourneyPhase.COMPLETED;
    }

    public String destinationName(Network network) {
        return service.destinationFrom(journey.lastIndex())
                .map(stop -> network.stationName(stop.stationId()))
                .orElse(service.name());
    }

    public Optional<String> nextStationId() {
        return nextIndex < 0 ? Optional.empty() : Optional.of(service.stop(nextIndex).stationId());
    }

    public boolean nextIsTerminus() {
        return nextIndex >= 0 && service.isTerminus(nextIndex);
    }
}
