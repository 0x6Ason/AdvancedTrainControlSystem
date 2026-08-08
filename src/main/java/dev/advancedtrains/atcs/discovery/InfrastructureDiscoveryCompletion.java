package dev.advancedtrains.atcs.discovery;

import java.util.Objects;

public record InfrastructureDiscoveryCompletion(
        boolean successful,
        String message,
        InfrastructureDiscoverySummary summary) {
    public InfrastructureDiscoveryCompletion {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(summary, "summary");
    }
}
