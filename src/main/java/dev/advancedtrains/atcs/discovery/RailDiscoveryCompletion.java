package dev.advancedtrains.atcs.discovery;

import java.util.Objects;

public record RailDiscoveryCompletion(boolean successful, String message, RailDiscoverySummary summary) {
    public RailDiscoveryCompletion {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(summary, "summary");
    }
}
