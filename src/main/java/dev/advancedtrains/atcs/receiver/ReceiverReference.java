package dev.advancedtrains.atcs.receiver;

import dev.advancedtrains.atcs.domain.DomainId;

import java.util.Objects;

/**
 * A standardized station/platform receiver reference such as CEN:P1.
 */
public record ReceiverReference(DomainId stationId, DomainId platformId) {
    public ReceiverReference {
        Objects.requireNonNull(stationId, "stationId");
        Objects.requireNonNull(platformId, "platformId");
    }

    public static ReceiverReference parse(String text) {
        Objects.requireNonNull(text, "text");
        String[] parts = text.strip().split(":", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Receiver reference must use STATION:PLATFORM format");
        }
        return new ReceiverReference(DomainId.of(parts[0]), DomainId.of(parts[1]));
    }

    @Override
    public String toString() {
        return stationId + ":" + platformId;
    }
}

