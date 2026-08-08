package dev.advancedtrains.atcs.persistence;

import java.nio.file.Path;
import java.util.Objects;

public record DatabaseConfiguration(
        Path databasePath,
        int busyTimeoutMillis,
        boolean backupBeforeMigration) {

    public DatabaseConfiguration {
        Objects.requireNonNull(databasePath, "databasePath");
        databasePath = databasePath.toAbsolutePath().normalize();
        if (busyTimeoutMillis < 0) {
            throw new IllegalArgumentException("Database busy timeout must not be negative");
        }
    }
}

