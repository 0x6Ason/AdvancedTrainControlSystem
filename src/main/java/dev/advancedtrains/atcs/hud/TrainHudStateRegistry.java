package dev.advancedtrains.atcs.hud;

import dev.advancedtrains.atcs.event.OperationalEventType;
import dev.advancedtrains.atcs.receiver.ReceiverTransition;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds short-lived, passenger-facing operational state derived from receiver transitions.
 */
public final class TrainHudStateRegistry {
    private static final Duration APPROACH_EXPIRY = Duration.ofMinutes(3);
    private static final Duration DEPARTURE_EXPIRY = Duration.ofSeconds(12);
    private final Map<String, TimedStatus> statuses = new ConcurrentHashMap<>();

    public void observe(ReceiverTransition transition) {
        String location = transition.receiver().stationId()
                + " (Platform " + transition.receiver().platformId() + ")";
        if (transition.eventType() == OperationalEventType.RECEIVER_ENTERING) {
            statuses.put(transition.trainName(), new TimedStatus(
                    "Approaching: " + location,
                    transition.occurredAt().plus(APPROACH_EXPIRY)));
        } else if (transition.eventType() == OperationalEventType.RECEIVER_CLEARED) {
            statuses.put(transition.trainName(), new TimedStatus(
                    "Departed: " + location,
                    transition.occurredAt().plus(DEPARTURE_EXPIRY)));
        }
    }

    public Optional<String> statusFor(String trainName, Instant now) {
        TimedStatus status = statuses.get(trainName);
        if (status == null) {
            return Optional.empty();
        }
        if (now.isAfter(status.expiresAt())) {
            statuses.remove(trainName, status);
            return Optional.empty();
        }
        return Optional.of(status.text());
    }

    private record TimedStatus(String text, Instant expiresAt) {
    }
}
