package dev.advancedtrains.atcs.infrastructure.review;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record CandidateEvidence(
        String kind,
        UUID sourceSignId,
        Optional<Double> distanceBlocks,
        Optional<String> evidenceText) {
    public CandidateEvidence {
        kind = Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(sourceSignId, "sourceSignId");
        distanceBlocks = Objects.requireNonNull(distanceBlocks, "distanceBlocks");
        evidenceText = Objects.requireNonNull(evidenceText, "evidenceText");
    }
}
