package dev.advancedtrains.atcs.integration;

import java.util.Objects;
import java.util.Optional;

public record DependencyStatus(String pluginName, boolean present, Optional<String> version) {
    public DependencyStatus {
        Objects.requireNonNull(pluginName, "pluginName");
        Objects.requireNonNull(version, "version");
        if (present && version.isEmpty()) {
            throw new IllegalArgumentException("A present dependency must report a version");
        }
        if (!present && version.isPresent()) {
            throw new IllegalArgumentException("A missing dependency cannot report a version");
        }
    }
}

