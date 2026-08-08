package dev.advancedtrains.atcs.infrastructure.review;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record CandidateDecisionCommand(
        UUID expectedRunId,
        CandidateDecisionAction action,
        int expectedVersion,
        Optional<String> note,
        Optional<CandidateAuthorityInput> authority,
        String actorId) {
    public CandidateDecisionCommand {
        Objects.requireNonNull(expectedRunId, "expectedRunId");
        Objects.requireNonNull(action, "action");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("Expected version cannot be negative");
        }
        note = Objects.requireNonNull(note, "note")
                .filter(value -> !value.isBlank())
                .map(String::strip);
        authority = Objects.requireNonNull(authority, "authority");
        actorId = Objects.requireNonNull(actorId, "actorId").strip();
        if (actorId.isEmpty()) {
            throw new IllegalArgumentException("Actor ID is required");
        }
        if (action == CandidateDecisionAction.INCLUDE && authority.isEmpty()) {
            throw new IllegalArgumentException("An include decision requires authority input");
        }
        if (action != CandidateDecisionAction.INCLUDE && authority.isPresent()) {
            throw new IllegalArgumentException("Only an include decision can contain authority input");
        }
    }
}
