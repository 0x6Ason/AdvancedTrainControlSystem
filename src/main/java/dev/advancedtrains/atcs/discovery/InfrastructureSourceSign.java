package dev.advancedtrains.atcs.discovery;

import dev.advancedtrains.atcs.domain.BlockPosition;
import dev.advancedtrains.atcs.legacy.LegacySignType;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record InfrastructureSourceSign(
        UUID id,
        LegacySignType type,
        BlockPosition position,
        Map<String, String> attributes) {
    public InfrastructureSourceSign {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(position, "position");
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
    }
}
