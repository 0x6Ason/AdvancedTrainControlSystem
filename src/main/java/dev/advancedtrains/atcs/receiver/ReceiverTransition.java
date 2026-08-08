package dev.advancedtrains.atcs.receiver;

import dev.advancedtrains.atcs.event.OperationalEventType;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ReceiverTransition(
        ReceiverReference receiver,
        String trainName,
        UUID passageId,
        OperationalEventType eventType,
        Instant occurredAt) {

    public ReceiverTransition {
        Objects.requireNonNull(receiver, "receiver");
        Objects.requireNonNull(trainName, "trainName");
        Objects.requireNonNull(passageId, "passageId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}

