package dev.advancedtrains.atcs.domain;

import java.util.Objects;

public record Platform(
        DomainId id,
        DomainId stationId,
        String displayName,
        BlockPosition stoppingReference,
        double berthToleranceBlocks,
        boolean enabled) {

    public Platform {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(stationId, "stationId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(stoppingReference, "stoppingReference");
        displayName = displayName.strip();
        if (displayName.isEmpty()) {
            throw new IllegalArgumentException("Platform display name must not be blank");
        }
        if (!Double.isFinite(berthToleranceBlocks) || berthToleranceBlocks <= 0) {
            throw new IllegalArgumentException("Berth tolerance must be finite and positive");
        }
    }
}

