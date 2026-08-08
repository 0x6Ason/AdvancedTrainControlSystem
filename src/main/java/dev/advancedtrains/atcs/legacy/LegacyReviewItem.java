package dev.advancedtrains.atcs.legacy;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record LegacyReviewItem(
        LegacyReviewSubject subject,
        LegacyReviewStatus status,
        Optional<String> reviewedBy,
        Optional<Instant> reviewedAt,
        Optional<String> reviewNote,
        List<LegacyReviewIssue> issues) {

    public LegacyReviewItem {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reviewedBy, "reviewedBy");
        Objects.requireNonNull(reviewedAt, "reviewedAt");
        Objects.requireNonNull(reviewNote, "reviewNote");
        Objects.requireNonNull(issues, "issues");
        issues = List.copyOf(issues);
    }

    public String shortId() {
        return subject.signId().toString().substring(0, 8);
    }
}
