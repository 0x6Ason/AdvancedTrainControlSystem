package dev.advancedtrains.atcs.discovery;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class StationNameExtractor {
    private final List<String> arrivalPrefixes;

    public StationNameExtractor(List<String> arrivalPrefixes) {
        Objects.requireNonNull(arrivalPrefixes, "arrivalPrefixes");
        this.arrivalPrefixes = arrivalPrefixes.stream()
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .toList();
        if (this.arrivalPrefixes.isEmpty()) {
            throw new IllegalArgumentException("At least one arrival prefix is required");
        }
    }

    public Optional<String> extract(String announcement) {
        String collapsed = collapseWhitespace(announcement);
        String normalized = collapsed.toLowerCase(Locale.ROOT);
        for (String prefix : arrivalPrefixes) {
            String normalizedPrefix = prefix.toLowerCase(Locale.ROOT);
            if (!normalized.startsWith(normalizedPrefix)) {
                continue;
            }
            String result = collapsed.substring(prefix.length()).strip();
            if (!result.isEmpty()) {
                return Optional.of(result);
            }
        }
        return Optional.empty();
    }

    public static String normalizeName(String value) {
        return collapseWhitespace(value).toLowerCase(Locale.ROOT);
    }

    private static String collapseWhitespace(String value) {
        return Objects.requireNonNullElse(value, "").strip().replaceAll("\\s+", " ");
    }
}
