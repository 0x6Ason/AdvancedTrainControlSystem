package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.PlatformRef;

import java.time.Instant;
import java.util.Objects;

/**
 * A train entered or left the rail of a platform trigger sign.
 *
 * @param trainId  TrainCarts train name
 * @param platform the platform the trigger belongs to
 * @param trigger  the trigger sign position; a platform may have several
 * @param entering {@code true} on enter, {@code false} on leave
 * @param at       when it happened
 */
public record StopObservation(String trainId, PlatformRef platform, BlockPos trigger, boolean entering, Instant at) {

    public StopObservation {
        Objects.requireNonNull(trainId, "trainId");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(at, "at");
    }

    public static StopObservation enter(String trainId, PlatformRef platform, BlockPos trigger, Instant at) {
        return new StopObservation(trainId, platform, trigger, true, at);
    }

    public static StopObservation leave(String trainId, PlatformRef platform, BlockPos trigger, Instant at) {
        return new StopObservation(trainId, platform, trigger, false, at);
    }
}
