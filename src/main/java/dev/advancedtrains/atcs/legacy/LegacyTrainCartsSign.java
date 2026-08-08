package dev.advancedtrains.atcs.legacy;

import dev.advancedtrains.atcs.domain.BlockPosition;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record LegacyTrainCartsSign(
        UUID id,
        BlockPosition position,
        int chunkX,
        int chunkZ,
        String side,
        LegacySignHeader header,
        LegacySignType type,
        List<String> rawLines,
        Map<String, String> attributes,
        int parserVersion) {

    public LegacyTrainCartsSign {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(rawLines, "rawLines");
        Objects.requireNonNull(attributes, "attributes");
        rawLines = List.copyOf(rawLines);
        attributes = Map.copyOf(attributes);
        if (rawLines.size() != 4) {
            throw new IllegalArgumentException("A sign inventory record must contain exactly four lines");
        }
        if (parserVersion <= 0) {
            throw new IllegalArgumentException("Parser version must be positive");
        }
    }
}

