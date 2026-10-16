package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.Service;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.OptionalDouble;

/**
 * What the estimator may know beyond learned times: where a running train is on its segment,
 * whether it is standing still, and how long ATCS itself will keep a train at a platform. The
 * runtime provides it; tests and the replay harness provide their own.
 */
public interface EtaContext {

    /** Share of the segment from stop {@code fromIndex} the train has covered, 0 to 1, when its position is known. */
    OptionalDouble segmentProgress(String trainId, Service service, int fromIndex);

    /** Whether the train has not moved for a while; a held train's countdown must not run on. */
    boolean standing(String trainId);

    /**
     * Seconds until ATCS lets the train leave the platform it stands at, when ATCS controls that
     * platform (configured dwell, headway, the train ahead); empty where only learned dwell applies.
     */
    OptionalDouble remainingStop(String trainId, Instant now);

    /** Why ATCS keeps the train at its platform right now, for displays; null when it is not held. */
    default @Nullable String holdReason(String trainId) {
        return null;
    }

    /** Learned times only. */
    EtaContext NONE = new EtaContext() {
        @Override
        public OptionalDouble segmentProgress(String trainId, Service service, int fromIndex) {
            return OptionalDouble.empty();
        }

        @Override
        public boolean standing(String trainId) {
            return false;
        }

        @Override
        public OptionalDouble remainingStop(String trainId, Instant now) {
            return OptionalDouble.empty();
        }
    };
}
