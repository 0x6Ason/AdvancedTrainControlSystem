package dev.advancedtrains.atcs.domain;

import java.util.Objects;

/**
 * An immutable block position that does not hold a live Bukkit world reference.
 */
public record BlockPosition(String worldName, int x, int y, int z) {
    public BlockPosition {
        Objects.requireNonNull(worldName, "worldName");
        worldName = worldName.strip();
        if (worldName.isEmpty()) {
            throw new IllegalArgumentException("World name must not be blank");
        }
    }
}

