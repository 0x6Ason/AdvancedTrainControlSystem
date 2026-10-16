package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.Service;

import java.time.Instant;
import java.util.OptionalDouble;

/** Answers "when does this train reach that stop". Every display asks one estimator, so they agree. */
public interface Estimator {

    /**
     * Seconds until the train reaches stop {@code target} of its service: zero while it stands
     * there, empty when the journey state or the learned timings are insufficient.
     */
    OptionalDouble secondsUntil(JourneySnapshot journey, Service service, int target, Instant now);
}
