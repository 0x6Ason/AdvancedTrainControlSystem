package dev.advancedtrains.atcs.discovery;

import dev.advancedtrains.atcs.infrastructure.review.CandidateDecisionReconciler;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class InfrastructureDiscoveryService implements AutoCloseable {
    private final JavaPlugin plugin;
    private final InfrastructureCandidateRepository repository;
    private final CandidateDecisionReconciler candidateDecisionReconciler;
    private final Logger logger;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile InfrastructureDiscoverySummary lastSummary;

    public InfrastructureDiscoveryService(
            JavaPlugin plugin,
            InfrastructureCandidateRepository repository,
            CandidateDecisionReconciler candidateDecisionReconciler,
            InfrastructureDiscoverySummary initialSummary,
            Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.candidateDecisionReconciler = Objects.requireNonNull(
                candidateDecisionReconciler, "candidateDecisionReconciler");
        this.lastSummary = Objects.requireNonNull(initialSummary, "initialSummary");
        this.logger = Objects.requireNonNull(logger, "logger");
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "atcs-infrastructure-discovery");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newSingleThreadExecutor(threadFactory);
    }

    public boolean startCandidateDiscovery(Consumer<InfrastructureDiscoveryCompletion> completion) {
        Objects.requireNonNull(completion, "completion");
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Candidate discovery must be started on the server thread");
        }
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        InfrastructureDiscoverySummary summary = repository.discover();
                        candidateDecisionReconciler.reconcile();
                        return summary;
                    } catch (SQLException exception) {
                        throw new DiscoveryPersistenceException(exception);
                    }
                }, executor)
                .whenComplete((summary, error) -> complete(summary, error, completion));
        return true;
    }

    public boolean running() {
        return running.get();
    }

    public InfrastructureDiscoverySummary lastSummary() {
        return lastSummary;
    }

    private void complete(
            InfrastructureDiscoverySummary summary,
            Throwable error,
            Consumer<InfrastructureDiscoveryCompletion> completion) {
        Runnable callback = () -> {
            running.set(false);
            if (error == null) {
                lastSummary = summary;
                completion.accept(new InfrastructureDiscoveryCompletion(
                        true,
                        "Infrastructure candidate discovery completed.",
                        summary));
            } else {
                logger.log(Level.SEVERE, "Could not create the infrastructure candidate snapshot", error);
                completion.accept(new InfrastructureDiscoveryCompletion(
                        false,
                        "Infrastructure candidate discovery failed. Check the server log.",
                        lastSummary));
            }
        };
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, callback);
        } else {
            running.set(false);
        }
    }

    @Override
    public void close() {
        running.set(false);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private static final class DiscoveryPersistenceException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private DiscoveryPersistenceException(Throwable cause) {
            super(cause);
        }
    }
}
