package dev.advancedtrains.atcs.infrastructure.review;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record CandidateDecision(
        CandidateDecisionStatus status,
        Optional<CandidateAuthorityRole> authorityRole,
        Optional<String> targetId,
        Optional<String> authorityDisplayName,
        Optional<String> note,
        int version,
        Optional<String> decidedBy,
        Optional<Instant> decidedAt,
        Optional<String> staleReason) {
    public CandidateDecision {
        Objects.requireNonNull(status, "status");
        authorityRole = Objects.requireNonNull(authorityRole, "authorityRole");
        targetId = Objects.requireNonNull(targetId, "targetId");
        authorityDisplayName = Objects.requireNonNull(authorityDisplayName, "authorityDisplayName");
        note = Objects.requireNonNull(note, "note");
        decidedBy = Objects.requireNonNull(decidedBy, "decidedBy");
        decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
        staleReason = Objects.requireNonNull(staleReason, "staleReason");
        if (version < 0) {
            throw new IllegalArgumentException("Decision version cannot be negative");
        }
    }

    public static CandidateDecision pending() {
        return new CandidateDecision(
                CandidateDecisionStatus.PENDING,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }
}
