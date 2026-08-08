package dev.advancedtrains.atcs.legacy;

import java.util.Objects;
import java.util.Optional;

public record LegacyScanCompletion(
        boolean successful,
        String message,
        Optional<LegacyScanResult> result,
        LegacyInventorySummary summary) {

    public LegacyScanCompletion {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(summary, "summary");
    }
}

