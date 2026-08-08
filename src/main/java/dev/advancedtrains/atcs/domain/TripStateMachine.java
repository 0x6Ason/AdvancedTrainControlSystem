package dev.advancedtrains.atcs.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Enforces operational trip state transitions independently of TrainCarts physics.
 */
public final class TripStateMachine {
    private static final Map<TripState, Set<TripState>> ALLOWED = createAllowedTransitions();

    private TripStateMachine() {
    }

    public static boolean canTransition(TripState from, TripState to) {
        return from == to || ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static void requireTransition(TripState from, TripState to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException("Invalid trip state transition: " + from + " -> " + to);
        }
    }

    private static Map<TripState, Set<TripState>> createAllowedTransitions() {
        Map<TripState, Set<TripState>> transitions = new EnumMap<>(TripState.class);
        transitions.put(TripState.PLANNED, EnumSet.of(TripState.READY, TripState.CANCELLED));
        transitions.put(TripState.READY, EnumSet.of(TripState.SPAWNING, TripState.HELD, TripState.CANCELLED));
        transitions.put(TripState.SPAWNING, EnumSet.of(TripState.IN_SERVICE, TripState.FAULT, TripState.CANCELLED));
        transitions.put(TripState.IN_SERVICE, EnumSet.of(
                TripState.APPROACHING, TripState.HELD, TripState.DELAYED, TripState.SHORT_TURNED,
                TripState.FAULT, TripState.RETURNING, TripState.CONTINUE_AS, TripState.COMPLETED));
        transitions.put(TripState.APPROACHING, EnumSet.of(
                TripState.DWELLING, TripState.IN_SERVICE, TripState.DELAYED, TripState.FAULT));
        transitions.put(TripState.DWELLING, EnumSet.of(
                TripState.IN_SERVICE, TripState.HELD, TripState.DELAYED, TripState.SHORT_TURNED,
                TripState.FAULT, TripState.RETURNING, TripState.CONTINUE_AS, TripState.COMPLETED));
        transitions.put(TripState.HELD, EnumSet.of(TripState.READY, TripState.IN_SERVICE, TripState.CANCELLED));
        transitions.put(TripState.DELAYED, EnumSet.of(
                TripState.IN_SERVICE, TripState.APPROACHING, TripState.DWELLING, TripState.HELD,
                TripState.SHORT_TURNED, TripState.FAULT, TripState.CANCELLED));
        transitions.put(TripState.SHORT_TURNED, EnumSet.of(
                TripState.IN_SERVICE, TripState.RETURNING, TripState.CONTINUE_AS, TripState.COMPLETED));
        transitions.put(TripState.FAULT, EnumSet.of(TripState.HELD, TripState.IN_SERVICE, TripState.CANCELLED));
        transitions.put(TripState.RETURNING, EnumSet.of(TripState.COMPLETED, TripState.FAULT));
        transitions.put(TripState.CONTINUE_AS, EnumSet.of(TripState.COMPLETED, TripState.FAULT));
        return Map.copyOf(transitions);
    }
}

