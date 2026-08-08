package dev.advancedtrains.atcs.legacy;

import dev.advancedtrains.atcs.domain.BlockPosition;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record LegacyReviewSubject(
        UUID signId,
        BlockPosition position,
        String side,
        LegacySignType type,
        String action,
        Optional<String> spawnPattern,
        boolean regularMinecartCountPresent,
        Optional<String> launchForce,
        List<String> rawLines) {

    public LegacyReviewSubject {
        Objects.requireNonNull(signId, "signId");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(spawnPattern, "spawnPattern");
        Objects.requireNonNull(launchForce, "launchForce");
        Objects.requireNonNull(rawLines, "rawLines");
        rawLines = List.copyOf(rawLines);
        if (rawLines.size() != 4) {
            throw new IllegalArgumentException("A review subject must preserve exactly four sign lines");
        }
    }
}
