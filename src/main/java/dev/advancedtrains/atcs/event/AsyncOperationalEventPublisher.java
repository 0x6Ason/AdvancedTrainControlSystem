package dev.advancedtrains.atcs.event;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Serializes event persistence away from the Minecraft server thread.
 */
public final class AsyncOperationalEventPublisher implements AutoCloseable {
    private final OperationalEventStore store;
    private final Logger logger;
    private final ExecutorService executor;
    private final List<Consumer<OperationalEvent>> subscribers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    public AsyncOperationalEventPublisher(OperationalEventStore store, Logger logger) {
        this.store = Objects.requireNonNull(store, "store");
        this.logger = Objects.requireNonNull(logger, "logger");
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "atcs-operational-events");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, throwable) ->
                    logger.log(Level.SEVERE, "Uncaught operational event worker failure", throwable));
            return thread;
        };
        this.executor = Executors.newSingleThreadExecutor(threadFactory);
    }

    public void subscribe(Consumer<OperationalEvent> subscriber) {
        subscribers.add(Objects.requireNonNull(subscriber, "subscriber"));
    }

    public CompletableFuture<PublishResult> publish(OperationalEvent event) {
        Objects.requireNonNull(event, "event");
        if (!accepting.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("The operational event publisher is shutting down"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!store.appendIfAbsent(event)) {
                    return PublishResult.DUPLICATE;
                }
                for (Consumer<OperationalEvent> subscriber : subscribers) {
                    try {
                        subscriber.accept(event);
                    } catch (RuntimeException exception) {
                        logger.log(Level.SEVERE,
                                "Operational event subscriber failed for " + event.id(), exception);
                    }
                }
                return PublishResult.PUBLISHED;
            } catch (Exception exception) {
                throw new EventPublishException("Failed to persist operational event " + event.id(), exception);
            }
        }, executor);
    }

    public boolean close(Duration timeout) {
        accepting.set(false);
        executor.shutdown();
        try {
            if (executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return true;
            }
            executor.shutdownNow();
            return executor.awaitTermination(Math.min(timeout.toMillis(), 1000), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            return false;
        }
    }

    @Override
    public void close() {
        close(Duration.ofSeconds(5));
    }

    public static final class EventPublishException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public EventPublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
