package dev.advancedtrains.atcs.config;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Configurable rules for flagging legacy signs for operator review.
 */
public record LegacyReviewConfiguration(
        Map<String, String> actionAliases,
        boolean flagComplexSpawnPatterns,
        double minimumLaunchForce,
        double maximumLaunchForce,
        int pageSize) {

    public LegacyReviewConfiguration {
        Objects.requireNonNull(actionAliases, "actionAliases");
        Map<String, String> normalizedAliases = new LinkedHashMap<>();
        actionAliases.forEach((alias, target) -> {
            String normalizedAlias = normalizeAction(alias, "Action alias");
            String normalizedTarget = normalizeAction(target, "Action alias target");
            if (normalizedAlias.equals(normalizedTarget)) {
                throw new IllegalArgumentException("An action alias cannot map to itself: " + normalizedAlias);
            }
            normalizedAliases.put(normalizedAlias, normalizedTarget);
        });
        actionAliases = Map.copyOf(normalizedAliases);

        if (!Double.isFinite(minimumLaunchForce) || minimumLaunchForce < 0) {
            throw new IllegalArgumentException("inventory-review.launch-force.minimum must be finite and non-negative");
        }
        if (!Double.isFinite(maximumLaunchForce) || maximumLaunchForce < 0) {
            throw new IllegalArgumentException("inventory-review.launch-force.maximum must be finite and non-negative");
        }
        if (maximumLaunchForce > 0
                && minimumLaunchForce > 0
                && maximumLaunchForce < minimumLaunchForce) {
            throw new IllegalArgumentException(
                    "inventory-review.launch-force.maximum cannot be lower than minimum");
        }
        if (pageSize <= 0 || pageSize > 50) {
            throw new IllegalArgumentException("inventory-review.page-size must be between 1 and 50");
        }
    }

    public static LegacyReviewConfiguration defaults() {
        return new LegacyReviewConfiguration(Map.of(), true, 0, 0, 8);
    }

    public boolean minimumLaunchForceEnabled() {
        return minimumLaunchForce > 0;
    }

    public boolean maximumLaunchForceEnabled() {
        return maximumLaunchForce > 0;
    }

    private static String normalizeAction(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return normalized;
    }
}
