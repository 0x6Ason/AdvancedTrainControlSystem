package dev.advancedtrains.atcs.infrastructure.review;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Coalesces candidate reconciliation requests and retries every committed generation until it succeeds.
 */
public final class CandidateReconciliationCoordinator implements CandidateDecisionReconciler, AutoCloseable {
    private static final long MAXIMUM_RETRY_DELAY_MILLIS = 5_000L;

    private final CandidateDecisionReconciler delegate;
    private final Logger logger;
    private final ScheduledExecutorService executor;
    private final AtomicLong requestedGeneration = new AtomicLong();
    private final AtomicLong completedGeneration = new AtomicLong();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private int consecutiveFailures;

    public CandidateReconciliationCoordinator(
            CandidateDecisionReconciler delegate,
            Logger logger) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.logger = Objects.requireNonNull(logger, "logger");
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "atcs-candidate-reconciliation");
            thread.setDaemon(true);
            return thread;
        };
        executor = Executors.newSingleThreadScheduledExecutor(threadFactory);
    }

    @Override
    public void reconcile() {
        if (closed.get()) {
            return;
        }
        requestedGeneration.incrementAndGet();
        schedule(0L);
    }

    public boolean pending() {
        return completedGeneration.get() < requestedGeneration.get();
    }

    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        long deadline = System.nanoTime() + timeout.toNanos();
        while (pending() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10L);
        }
        return !pending();
    }

    private void schedule(long delayMillis) {
        if (closed.get() || !scheduled.compareAndSet(false, true)) {
            return;
        }
        executor.schedule(this::drain, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void drain() {
        long targetGeneration = requestedGeneration.get();
        long retryDelayMillis = 0L;
        try {
            delegate.reconcile();
            completedGeneration.accumulateAndGet(targetGeneration, Math::max);
            consecutiveFailures = 0;
        } catch (SQLException | RuntimeException exception) {
            consecutiveFailures++;
            retryDelayMillis = Math.min(
                    MAXIMUM_RETRY_DELAY_MILLIS,
                    100L << Math.min(consecutiveFailures - 1, 6));
            if (consecutiveFailures == 1 || consecutiveFailures % 12 == 0) {
                logger.log(Level.WARNING,
                        "Candidate reconciliation is pending and will be retried",
                        exception);
            }
        } finally {
            scheduled.set(false);
            if (!closed.get() && completedGeneration.get() < requestedGeneration.get()) {
                schedule(retryDelayMillis);
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
