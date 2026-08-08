package dev.advancedtrains.atcs.infrastructure.review;

import java.util.Objects;
import java.util.Optional;

public record CandidateAuthorityInput(
        Optional<String> targetId,
        Optional<String> displayName) {
    public CandidateAuthorityInput {
        targetId = clean(Objects.requireNonNull(targetId, "targetId"));
        displayName = clean(Objects.requireNonNull(displayName, "displayName"));
    }

    private static Optional<String> clean(Optional<String> value) {
        return value.filter(item -> !item.isBlank()).map(String::strip);
    }
}
