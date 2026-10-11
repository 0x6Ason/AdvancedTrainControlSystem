package dev.advancedtrains.atcs.runtime;

import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Fleet;
import org.jspecify.annotations.Nullable;

/** Creates trains. Implemented by the TrainCarts adapter. */
public interface TrainSpawner {

    /**
     * @param train   the new train, or {@code null} when nothing was spawned
     * @param problem why nothing was spawned, in plain words; empty on success
     */
    record Result(@Nullable TrainHandle train, String problem) {
        public static Result failed(String problem) {
            return new Result(null, problem);
        }

        public boolean succeeded() {
            return train != null;
        }
    }

    /** Spawns a train on the rail at {@code at}, setting off in {@code direction}. */
    Result spawn(BlockPos at, Fleet.Direction direction, String pattern);
}
