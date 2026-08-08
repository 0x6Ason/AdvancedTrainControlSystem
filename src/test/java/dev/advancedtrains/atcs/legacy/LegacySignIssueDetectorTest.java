package dev.advancedtrains.atcs.legacy;

import dev.advancedtrains.atcs.config.LegacyReviewConfiguration;
import dev.advancedtrains.atcs.domain.BlockPosition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacySignIssueDetectorTest {
    @Test
    void flagsConfiguredActionAliasesWithoutCorrectingThem() {
        LegacySignIssueDetector detector = new LegacySignIssueDetector(
                new LegacyReviewConfiguration(Map.of("annnounce", "announce"), true, 0, 0, 8));
        LegacyReviewSubject subject = subject(
                LegacySignType.OTHER, "annnounce", Optional.empty(), false, Optional.empty());

        List<LegacyReviewIssue> issues = detector.detect(subject);

        assertEquals(1, issues.size());
        assertEquals(LegacyIssueCode.ACTION_ALIAS, issues.getFirst().code());
        assertTrue(issues.getFirst().details().contains("correct the sign"));
    }

    @Test
    void flagsComplexPatternsAndConfiguredForceRange() {
        LegacySignIssueDetector detector = new LegacySignIssueDetector(
                new LegacyReviewConfiguration(Map.of(), true, 100, 10_000, 8));
        LegacyReviewSubject subject = subject(
                LegacySignType.SPAWN,
                "spawn",
                Optional.of("2m24s2m"),
                false,
                Optional.of("4"));

        List<LegacyReviewIssue> issues = detector.detect(subject);

        assertEquals(
                List.of(LegacyIssueCode.COMPLEX_SPAWN_PATTERN, LegacyIssueCode.LAUNCH_FORCE_OUT_OF_RANGE),
                issues.stream().map(LegacyReviewIssue::code).toList());
    }

    @Test
    void leavesSimplePatternsAloneWhenForceBoundariesAreDisabled() {
        LegacySignIssueDetector detector = new LegacySignIssueDetector(LegacyReviewConfiguration.defaults());
        LegacyReviewSubject subject = subject(
                LegacySignType.SPAWN, "spawn", Optional.of("12m"), true, Optional.of("9999"));

        assertTrue(detector.detect(subject).isEmpty());
    }

    private LegacyReviewSubject subject(
            LegacySignType type,
            String action,
            Optional<String> spawnPattern,
            boolean countPresent,
            Optional<String> launchForce) {
        return new LegacyReviewSubject(
                UUID.randomUUID(),
                new BlockPosition("world", 1, 64, 2),
                "FRONT",
                type,
                action,
                spawnPattern,
                countPresent,
                launchForce,
                List.of("[+train]", action, spawnPattern.orElse(""), ""));
    }
}
