package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.network.ServiceKey;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;

/** Something passengers or displays may need to know about a train's progress. */
public sealed interface JourneyEvent {

    String trainId();

    Instant at();

    /**
     * The train arrived at a calling stop of its service.
     *
     * @param fromIndex  the stop it came from when that was the previous stop, otherwise -1
     * @param travelTime time since leaving {@code fromIndex}, or {@code null}
     */
    record Arrived(
            String trainId,
            Instant at,
            ServiceKey service,
            int stopIndex,
            PlatformRef platform,
            boolean terminus,
            int fromIndex,
            @Nullable Duration travelTime) implements JourneyEvent {
    }

    /**
     * The train left a calling stop.
     *
     * @param nextIndex the next calling stop, or -1 when the trip has ended
     * @param dwell     time spent at the stop, or {@code null}
     */
    record Departed(
            String trainId,
            Instant at,
            ServiceKey service,
            int stopIndex,
            PlatformRef platform,
            int nextIndex,
            @Nullable Duration dwell) implements JourneyEvent {
    }

    /**
     * The train ran through a station without calling: a pass stop of its service
     * ({@code stopIndex >= 0}) or a station its service does not list ({@code stopIndex == -1}).
     */
    record Passed(
            String trainId,
            Instant at,
            ServiceKey service,
            int stopIndex,
            PlatformRef platform,
            int fromIndex,
            @Nullable Duration travelTime) implements JourneyEvent {
    }

    /** The train is close to its next calling stop. */
    record Approaching(String trainId, Instant at, ServiceKey service, int stopIndex) implements JourneyEvent {
    }

    /** A train without a service arrived at a known platform. */
    record UnassignedArrived(String trainId, Instant at, PlatformRef platform) implements JourneyEvent {
    }

    /** A train without a service left a known platform. */
    record UnassignedDeparted(String trainId, Instant at, PlatformRef platform) implements JourneyEvent {
    }

    /** The train's service changed, including continuing as another service at a terminus. */
    record ServiceChanged(
            String trainId,
            Instant at,
            @Nullable ServiceKey from,
            @Nullable ServiceKey to) implements JourneyEvent {
    }

    /** The train left the last stop of a non-circular service with no continuation. */
    record TripCompleted(String trainId, Instant at, ServiceKey service) implements JourneyEvent {
    }
}
