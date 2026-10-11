package dev.advancedtrains.atcs.network;

import java.util.Objects;

/** Identifies one platform of one station. */
public record PlatformRef(String stationId, String platformId) {

    public PlatformRef {
        Objects.requireNonNull(stationId, "stationId");
        Objects.requireNonNull(platformId, "platformId");
    }

    @Override
    public String toString() {
        return stationId + ":" + platformId;
    }
}
