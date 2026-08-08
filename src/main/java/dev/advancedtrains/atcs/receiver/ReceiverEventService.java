package dev.advancedtrains.atcs.receiver;

import dev.advancedtrains.atcs.event.AsyncOperationalEventPublisher;
import dev.advancedtrains.atcs.event.OperationalEvent;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ReceiverEventService {
    private final ReceiverStateTracker stateTracker;
    private final AsyncOperationalEventPublisher publisher;
    private final Clock clock;
    private final Logger logger;
    private final List<Consumer<ReceiverTransition>> transitionListeners = new CopyOnWriteArrayList<>();

    public ReceiverEventService(
            ReceiverStateTracker stateTracker,
            AsyncOperationalEventPublisher publisher,
            Clock clock,
            Logger logger) {
        this.stateTracker = Objects.requireNonNull(stateTracker, "stateTracker");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void onEnter(ReceiverReference receiver, String trainName) {
        publish(stateTracker.enter(receiver, trainName, Instant.now(clock)));
    }

    public void onLeave(ReceiverReference receiver, String trainName) {
        publish(stateTracker.leave(receiver, trainName, Instant.now(clock)));
    }

    public int trackedPassageCount() {
        return stateTracker.trackedPassageCount();
    }

    public void subscribeTransitions(Consumer<ReceiverTransition> listener) {
        transitionListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    private void publish(Optional<ReceiverTransition> transition) {
        transition.ifPresent(value -> {
            for (Consumer<ReceiverTransition> listener : transitionListeners) {
                try {
                    listener.accept(value);
                } catch (RuntimeException exception) {
                    logger.log(Level.SEVERE, "Receiver transition listener failed", exception);
                }
            }
            String idempotencyKey = String.join(":",
                    "receiver",
                    value.receiver().stationId().value(),
                    value.receiver().platformId().value(),
                    value.trainName(),
                    value.passageId().toString(),
                    value.eventType().name());
            OperationalEvent event = OperationalEvent.create(
                    idempotencyKey,
                    value.eventType(),
                    value.occurredAt(),
                    Map.of(
                            "station_id", value.receiver().stationId().value(),
                            "platform_id", value.receiver().platformId().value(),
                            "train_name", value.trainName(),
                            "passage_id", value.passageId().toString(),
                            "source", "TRAINCARTS_RAILRCV"));
            CompletableFuture<?> result = publisher.publish(event);
            result.whenComplete((ignored, error) -> {
                if (error != null) {
                    logger.log(Level.SEVERE, "Failed to publish receiver event " + event.id(), error);
                }
            });
        });
    }
}
