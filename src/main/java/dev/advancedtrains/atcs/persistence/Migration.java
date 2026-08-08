package dev.advancedtrains.atcs.persistence;

import java.util.Objects;

public record Migration(int version, String name, String resourcePath) {
    public Migration {
        if (version <= 0) {
            throw new IllegalArgumentException("Migration version must be positive");
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(resourcePath, "resourcePath");
    }
}

