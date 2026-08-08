package dev.advancedtrains.atcs.legacy;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record LegacyScanResult(
        UUID runId,
        Instant startedAt,
        Instant completedAt,
        List<LegacyScanChunk> scannedChunks,
        int skippedChunks,
        List<LegacyTrainCartsSign> signs) {

    public LegacyScanResult {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(completedAt, "completedAt");
        Objects.requireNonNull(scannedChunks, "scannedChunks");
        Objects.requireNonNull(signs, "signs");
        scannedChunks = List.copyOf(scannedChunks);
        signs = List.copyOf(signs);
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("Scan completion time cannot be before its start time");
        }
        if (skippedChunks < 0) {
            throw new IllegalArgumentException("Skipped chunk count must not be negative");
        }
    }
}

