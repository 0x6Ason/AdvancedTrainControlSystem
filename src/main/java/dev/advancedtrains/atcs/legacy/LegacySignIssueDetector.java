package dev.advancedtrains.atcs.legacy;

import dev.advancedtrains.atcs.config.LegacyReviewConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class LegacySignIssueDetector {
    private final LegacyReviewConfiguration configuration;

    public LegacySignIssueDetector(LegacyReviewConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    public List<LegacyReviewIssue> detect(LegacyReviewSubject subject) {
        Objects.requireNonNull(subject, "subject");
        List<LegacyReviewIssue> issues = new ArrayList<>();
        String aliasTarget = configuration.actionAliases().get(subject.action());
        if (aliasTarget != null) {
            issues.add(new LegacyReviewIssue(
                    LegacyIssueCode.ACTION_ALIAS,
                    LegacyIssueSeverity.WARNING,
                    "Action '" + subject.action() + "' matches the configured alias '"
                            + aliasTarget + "'; correct the sign before adoption."));
        }

        if (subject.type() == LegacySignType.SPAWN) {
            detectSpawnIssues(subject, issues);
        }
        return List.copyOf(issues);
    }

    private void detectSpawnIssues(LegacyReviewSubject subject, List<LegacyReviewIssue> issues) {
        if (configuration.flagComplexSpawnPatterns()
                && subject.spawnPattern().isPresent()
                && !subject.regularMinecartCountPresent()) {
            issues.add(new LegacyReviewIssue(
                    LegacyIssueCode.COMPLEX_SPAWN_PATTERN,
                    LegacyIssueSeverity.WARNING,
                    "Spawn pattern '" + subject.spawnPattern().orElseThrow()
                            + "' is not a simple regular-minecart pattern and requires manual review."));
        }

        subject.launchForce().ifPresent(value -> detectLaunchForce(value, issues));
    }

    private void detectLaunchForce(String value, List<LegacyReviewIssue> issues) {
        double parsed;
        try {
            parsed = Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            issues.add(new LegacyReviewIssue(
                    LegacyIssueCode.INVALID_LAUNCH_FORCE,
                    LegacyIssueSeverity.ERROR,
                    "Launch force '" + value + "' is not numeric."));
            return;
        }

        boolean belowMinimum = configuration.minimumLaunchForceEnabled()
                && parsed < configuration.minimumLaunchForce();
        boolean aboveMaximum = configuration.maximumLaunchForceEnabled()
                && parsed > configuration.maximumLaunchForce();
        if (belowMinimum || aboveMaximum) {
            issues.add(new LegacyReviewIssue(
                    LegacyIssueCode.LAUNCH_FORCE_OUT_OF_RANGE,
                    LegacyIssueSeverity.WARNING,
                    "Launch force '" + value + "' is outside the configured review range."));
        }
    }
}
