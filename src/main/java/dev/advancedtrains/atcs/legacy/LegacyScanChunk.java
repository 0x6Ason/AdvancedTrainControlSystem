package dev.advancedtrains.atcs.legacy;

import java.util.Objects;

public record LegacyScanChunk(String worldName, int chunkX, int chunkZ) {
    public LegacyScanChunk {
        Objects.requireNonNull(worldName, "worldName");
        worldName = worldName.strip();
        if (worldName.isEmpty()) {
            throw new IllegalArgumentException("World name must not be blank");
        }
    }
}

