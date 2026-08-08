package dev.advancedtrains.atcs.domain;

import java.util.Objects;

public record Station(DomainId id, String displayName, String worldName, boolean enabled) {
    public Station {
        Objects.requireNonNull(id, "id");
        displayName = requireText(displayName, "displayName");
        worldName = requireText(worldName, "worldName");
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        value = value.strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}

