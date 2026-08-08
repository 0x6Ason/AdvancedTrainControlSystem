package dev.advancedtrains.atcs.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TripStateMachineTest {
    @Test
    void acceptsNormalServiceProgression() {
        assertTrue(TripStateMachine.canTransition(TripState.PLANNED, TripState.READY));
        assertTrue(TripStateMachine.canTransition(TripState.READY, TripState.SPAWNING));
        assertTrue(TripStateMachine.canTransition(TripState.SPAWNING, TripState.IN_SERVICE));
        assertTrue(TripStateMachine.canTransition(TripState.IN_SERVICE, TripState.APPROACHING));
        assertTrue(TripStateMachine.canTransition(TripState.APPROACHING, TripState.DWELLING));
        assertTrue(TripStateMachine.canTransition(TripState.DWELLING, TripState.COMPLETED));
    }

    @Test
    void rejectsUnsafeStateJump() {
        assertFalse(TripStateMachine.canTransition(TripState.PLANNED, TripState.IN_SERVICE));
        assertThrows(IllegalStateException.class,
                () -> TripStateMachine.requireTransition(TripState.COMPLETED, TripState.SPAWNING));
        assertDoesNotThrow(() -> TripStateMachine.requireTransition(TripState.HELD, TripState.IN_SERVICE));
    }
}

