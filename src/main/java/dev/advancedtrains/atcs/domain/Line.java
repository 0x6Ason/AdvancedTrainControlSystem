package dev.advancedtrains.atcs.domain;

import java.util.Objects;

public record Line(DomainId id, String displayName, int colorRgb, boolean enabled) {
    public Line {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        displayName = displayName.strip();
        if (displayName.isEmpty()) {
            throw new IllegalArgumentException("Line display name must not be blank");
        }
        if (colorRgb < 0 || colorRgb > 0xFFFFFF) {
            throw new IllegalArgumentException("Line color must be a 24-bit RGB value");
        }
    }
}

