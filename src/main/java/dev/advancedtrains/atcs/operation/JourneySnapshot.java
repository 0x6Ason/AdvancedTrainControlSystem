package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * An immutable view of a train's journey.
 *
 * @param lastIndex    the last stop index reached (arrived at or passed), -1 if none yet
 * @param platform     the platform the train is at while {@code AT_STOP} or {@code PASSING}
 * @param arrivedAt    when the current or most recent station visit began
 * @param departedAt   when the most recent station visit ended
 * @param segmentStart when the train left stop {@code lastIndex}, if that is still the segment it
 *                     is running; used to estimate arrival times
 */
public record JourneySnapshot(
        String trainId,
        @Nullable ServiceKey service,
        JourneyPhase phase,
        int lastIndex,
        @Nullable PlatformRef platform,
        @Nullable Instant arrivedAt,
        @Nullable Instant departedAt,
        @Nullable Instant segmentStart,
        Instant lastSeen) {

    /** The station the train is standing at, if any. */
    public @Nullable String currentStationId() {
        return platform == null ? null : platform.stationId();
    }

    /**
     * The next stop the train will call at: while standing at a stop this is the stop after it.
     * Returns -1 when unknown or when the trip has ended.
     */
    public int nextCallingIndex(Service service) {
        return switch (phase) {
            case COMPLETED -> -1;
            case IDLE -> service.nextCallingIndex(-1);
            default -> service.nextCallingIndex(lastIndex);
        };
    }
}
