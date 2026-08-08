package dev.advancedtrains.atcs.event;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable evidence emitted by railway operations. The idempotency key represents the real-world fact.
 */
public record OperationalEvent(
        UUID id,
        String idempotencyKey,
        OperationalEventType type,
        Instant occurredAt,
        Map<String, String> attributes) {

    public OperationalEvent {
        Objects.requireNonNull(id, "id");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(attributes, "attributes");
        attributes = Map.copyOf(attributes);
        attributes.forEach((key, value) -> {
            requireText(key, "attribute key");
            Objects.requireNonNull(value, "attribute value for " + key);
        });
    }

    public static OperationalEvent create(
            String idempotencyKey,
            OperationalEventType type,
            Instant occurredAt,
            Map<String, String> attributes) {
        return new OperationalEvent(UUID.randomUUID(), idempotencyKey, type, occurredAt, attributes);
    }

    public static String tripVisitKey(
            UUID tripId,
            String stationId,
            String platformId,
            int visitIndex,
            OperationalEventType type) {
        if (visitIndex < 0) {
            throw new IllegalArgumentException("Visit index must not be negative");
        }
        return String.join(":",
                "trip", tripId.toString(), stationId, platformId, Integer.toString(visitIndex), type.name());
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        value = value.strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}

