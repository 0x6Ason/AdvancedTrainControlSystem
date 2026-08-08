package dev.advancedtrains.atcs.infrastructure.review;

import java.util.Objects;

public record CandidateDecisionResult(
        CandidateReviewItem candidate,
        boolean authorityChanged) {
    public CandidateDecisionResult {
        Objects.requireNonNull(candidate, "candidate");
    }
}
