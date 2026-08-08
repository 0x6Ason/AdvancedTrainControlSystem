package dev.advancedtrains.atcs.legacy;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record LegacyInventorySummary(
        Optional<UUID> lastRunId,
        Optional<Instant> lastCompletedAt,
        int chunksScanned,
        int chunksSkipped,
        int presentSigns,
        Map<LegacySignType, Integer> signsByType) {

    public LegacyInventorySummary {
        Objects.requireNonNull(lastRunId, "lastRunId");
        Objects.requireNonNull(lastCompletedAt, "lastCompletedAt");
        Objects.requireNonNull(signsByType, "signsByType");
        signsByType = Map.copyOf(signsByType);
    }

    public static LegacyInventorySummary empty() {
        return new LegacyInventorySummary(
                Optional.empty(), Optional.empty(), 0, 0, 0, Map.of());
    }
}

