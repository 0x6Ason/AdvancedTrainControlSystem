package dev.advancedtrains.atcs.hud;

import java.util.Objects;

public record PassengerHudSnapshot(
        String line,
        String serviceType,
        String destination,
        String nextStation,
        String operationalStatus) {

    public PassengerHudSnapshot {
        line = requireText(line, "line");
        serviceType = requireText(serviceType, "serviceType");
        destination = requireText(destination, "destination");
        nextStation = requireText(nextStation, "nextStation");
        operationalStatus = requireText(operationalStatus, "operationalStatus");
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

