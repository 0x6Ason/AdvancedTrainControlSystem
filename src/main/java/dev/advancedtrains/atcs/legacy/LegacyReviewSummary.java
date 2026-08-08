package dev.advancedtrains.atcs.legacy;

import java.util.Map;
import java.util.Objects;

public record LegacyReviewSummary(
        int presentSigns,
        Map<LegacyReviewStatus, Integer> signsByStatus,
        int issueSigns,
        int openIssues,
        Map<LegacyIssueCode, Integer> issuesByCode) {

    public LegacyReviewSummary {
        Objects.requireNonNull(signsByStatus, "signsByStatus");
        Objects.requireNonNull(issuesByCode, "issuesByCode");
        signsByStatus = Map.copyOf(signsByStatus);
        issuesByCode = Map.copyOf(issuesByCode);
    }

    public int statusCount(LegacyReviewStatus status) {
        return signsByStatus.getOrDefault(status, 0);
    }
}
