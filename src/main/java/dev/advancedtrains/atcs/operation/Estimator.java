package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalDouble;

/** Answers "when does this train reach that stop". Every display asks one estimator, so they agree. */
public interface Estimator {

    /**
     * An estimate and how much to trust it.
     *
     * @param seconds     seconds until the train reaches the stop; zero while it stands there
     * @param approximate part of it is a guess from track distance rather than learned from trains
     * @param late        the train has been on its way longer than trains usually take, so the
     *                    arrival is overdue: displays say "Delayed" instead of counting down
     */
    record Estimate(double seconds, boolean approximate, boolean late) {
        public Estimate plus(double more, boolean moreApproximate) {
            return new Estimate(seconds + more, approximate || moreApproximate, late);
        }

        public Estimate withSeconds(double newSeconds) {
            return new Estimate(newSeconds, approximate, late);
        }
    }

    /**
     * The estimate for the train reaching stop {@code target} of its service, or empty when the
     * journey state or the learned timings are insufficient.
     */
    Optional<Estimate> estimate(JourneySnapshot journey, Service service, int target, Instant now);

    /**
     * Seconds until the train reaches stop {@code target} of its service: zero while it stands
     * there, empty when the journey state or the learned timings are insufficient.
     */
    default OptionalDouble secondsUntil(JourneySnapshot journey, Service service, int target, Instant now) {
        return estimate(journey, service, target, now).map(estimate -> OptionalDouble.of(estimate.seconds()))
                .orElse(OptionalDouble.empty());
    }
}
