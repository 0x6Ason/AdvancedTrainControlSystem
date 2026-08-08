package dev.advancedtrains.atcs.legacy;

import java.util.Objects;

public record LegacyReviewIssue(
        LegacyIssueCode code,
        LegacyIssueSeverity severity,
        String details) {

    public LegacyReviewIssue {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(details, "details");
        if (details.isBlank()) {
            throw new IllegalArgumentException("Review issue details cannot be blank");
        }
    }
}
