package dev.advancedtrains.atcs.infrastructure.review;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record CandidateReviewItem(
        UUID discoveryRunId,
        Instant discoveredAt,
        CandidateKind kind,
        UUID candidateId,
        UUID sourceSignId,
        String worldName,
        int blockX,
        int blockY,
        int blockZ,
        String signSide,
        String actionType,
        List<String> rawLines,
        Map<String, String> attributes,
        double confidence,
        Optional<CandidateAuthorityRole> suggestedRole,
        Map<String, String> candidateProperties,
        List<CandidateEvidence> evidence,
        String evidenceFingerprint,
        int evidenceRevision,
        CandidateDecision decision) {
    public CandidateReviewItem {
        Objects.requireNonNull(discoveryRunId, "discoveryRunId");
        Objects.requireNonNull(discoveredAt, "discoveredAt");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(sourceSignId, "sourceSignId");
        worldName = Objects.requireNonNull(worldName, "worldName");
        signSide = Objects.requireNonNull(signSide, "signSide");
        actionType = Objects.requireNonNull(actionType, "actionType");
        rawLines = List.copyOf(Objects.requireNonNull(rawLines, "rawLines"));
        if (rawLines.size() != 4) {
            throw new IllegalArgumentException("Candidate evidence must contain four sign lines");
        }
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
        suggestedRole = Objects.requireNonNull(suggestedRole, "suggestedRole");
        candidateProperties = Map.copyOf(Objects.requireNonNull(candidateProperties, "candidateProperties"));
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        evidenceFingerprint = Objects.requireNonNull(evidenceFingerprint, "evidenceFingerprint");
        if (evidenceRevision < 1) {
            throw new IllegalArgumentException("Evidence revision must be positive");
        }
        Objects.requireNonNull(decision, "decision");
    }
}
