package dev.advancedtrains.atcs.web;

import java.util.Locale;

/**
 * Controls whether the administration map receives the relevant track display or all rail evidence.
 */
public enum RailGraphScope {
    NETWORK,
    ALL_EVIDENCE;

    public static RailGraphScope fromQuery(String rawValue) {
        if (rawValue == null) {
            return NETWORK;
        }
        String normalized = rawValue.strip().toUpperCase(Locale.ROOT);
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "scope must be NETWORK or ALL_EVIDENCE", exception);
        }
    }
}
