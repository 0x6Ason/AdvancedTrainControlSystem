package dev.advancedtrains.atcs.legacy;

import java.util.List;
import java.util.Objects;

public record LegacyReviewPage(int page, int pageSize, int totalItems, List<LegacyReviewItem> items) {
    public LegacyReviewPage {
        if (page <= 0 || pageSize <= 0 || totalItems < 0) {
            throw new IllegalArgumentException("Review page values are outside their valid range");
        }
        Objects.requireNonNull(items, "items");
        items = List.copyOf(items);
    }

    public int totalPages() {
        return Math.max(1, (totalItems + pageSize - 1) / pageSize);
    }
}
