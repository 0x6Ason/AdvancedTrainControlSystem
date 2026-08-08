package dev.advancedtrains.atcs.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A stable, human-readable identifier used by configuration and the database.
 */
public record DomainId(String value) implements Comparable<DomainId> {
    private static final Pattern VALID_VALUE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{0,63}");

    public DomainId {
        Objects.requireNonNull(value, "value");
        value = value.strip().toUpperCase(Locale.ROOT);
        if (!VALID_VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Domain ID must contain 1-64 ASCII letters, digits, underscores, or hyphens: " + value);
        }
    }

    public static DomainId of(String value) {
        return new DomainId(value);
    }

    @Override
    public int compareTo(DomainId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}

