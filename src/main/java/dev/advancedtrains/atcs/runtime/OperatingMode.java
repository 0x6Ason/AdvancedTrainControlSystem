package dev.advancedtrains.atcs.runtime;

import java.util.Locale;

public enum OperatingMode {
    READ_ONLY,
    ACTIVE;

    public static OperatingMode parse(String value) {
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException("operating-mode must be READ_ONLY or ACTIVE", exception);
        }
    }
}

