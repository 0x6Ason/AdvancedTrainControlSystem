package dev.advancedtrains.atcs.discovery;

import dev.advancedtrains.atcs.domain.BlockPosition;
import dev.advancedtrains.atcs.legacy.LegacySignType;

import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record InfrastructureSourceSign(
        UUID id,
        LegacySignType type,
        BlockPosition position,
        String signSide,
        String headerLine,
        List<String> rawLines,
        int parserVersion,
        Map<String, String> attributes) {
    public InfrastructureSourceSign {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(position, "position");
        signSide = Objects.requireNonNull(signSide, "signSide");
        headerLine = Objects.requireNonNull(headerLine, "headerLine");
        rawLines = List.copyOf(Objects.requireNonNull(rawLines, "rawLines"));
        if (rawLines.size() != 4) {
            throw new IllegalArgumentException("A source sign must preserve exactly four raw lines");
        }
        if (parserVersion < 1) {
            throw new IllegalArgumentException("Parser version must be positive");
        }
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
    }

    public InfrastructureSourceSign(
            UUID id,
            LegacySignType type,
            BlockPosition position,
            Map<String, String> attributes) {
        this(id, type, position, "FRONT", "", List.of("", "", "", ""), 1, attributes);
    }
}
