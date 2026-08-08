package dev.advancedtrains.atcs.domain;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public record ServiceCall(
        int visitIndex,
        DomainId stationId,
        Optional<DomainId> platformId,
        StopPolicy stopPolicy,
        Duration dwellTime) {

    public ServiceCall {
        if (visitIndex < 0) {
            throw new IllegalArgumentException("Visit index must not be negative");
        }
        Objects.requireNonNull(stationId, "stationId");
        Objects.requireNonNull(platformId, "platformId");
        Objects.requireNonNull(stopPolicy, "stopPolicy");
        Objects.requireNonNull(dwellTime, "dwellTime");
        if (dwellTime.isNegative()) {
            throw new IllegalArgumentException("Dwell time must not be negative");
        }
        if ((stopPolicy == StopPolicy.PASS || stopPolicy == StopPolicy.NON_SERVICE) && !dwellTime.isZero()) {
            throw new IllegalArgumentException("Pass and non-service calls cannot have dwell time");
        }
    }
}

