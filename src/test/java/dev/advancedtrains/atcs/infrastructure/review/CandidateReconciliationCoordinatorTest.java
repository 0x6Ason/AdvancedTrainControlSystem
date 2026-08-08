package dev.advancedtrains.atcs.infrastructure.review;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateReconciliationCoordinatorTest {
    @Test
    void retriesFailedGenerationAndCoalescesEveryPendingRequest() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CandidateDecisionReconciler delegate = () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new SQLException("simulated write contention");
            }
        };

        try (CandidateReconciliationCoordinator coordinator =
                     new CandidateReconciliationCoordinator(
                             delegate,
                             Logger.getLogger("candidate-reconciliation-test"))) {
            coordinator.reconcile();
            coordinator.reconcile();
            coordinator.reconcile();

            assertTrue(coordinator.pending());
            assertTrue(coordinator.awaitIdle(Duration.ofSeconds(3)));
            assertFalse(coordinator.pending());
            assertEquals(2, attempts.get());
        }
    }
}
