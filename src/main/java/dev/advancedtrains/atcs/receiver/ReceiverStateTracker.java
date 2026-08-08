package dev.advancedtrains.atcs.receiver;

import dev.advancedtrains.atcs.event.OperationalEventType;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Suppresses repeated TrainCarts enter/leave callbacks during one live receiver passage.
 */
public final class ReceiverStateTracker {
    private final Map<Key, Passage> passages = new HashMap<>();

    public synchronized Optional<ReceiverTransition> enter(
            ReceiverReference receiver,
            String trainName,
            Instant occurredAt) {
        Key key = Key.create(receiver, trainName);
        Passage previous = passages.get(key);
        if (previous != null && previous.active()) {
            return Optional.empty();
        }
        UUID passageId = UUID.randomUUID();
        passages.put(key, new Passage(passageId, true, occurredAt));
        return Optional.of(new ReceiverTransition(
                receiver, key.trainName(), passageId, OperationalEventType.RECEIVER_ENTERING, occurredAt));
    }

    public synchronized Optional<ReceiverTransition> leave(
            ReceiverReference receiver,
            String trainName,
            Instant occurredAt) {
        Key key = Key.create(receiver, trainName);
        Passage previous = passages.get(key);
        if (previous == null || !previous.active()) {
            return Optional.empty();
        }
        passages.put(key, new Passage(previous.id(), false, occurredAt));
        return Optional.of(new ReceiverTransition(
                receiver, key.trainName(), previous.id(), OperationalEventType.RECEIVER_CLEARED, occurredAt));
    }

    public synchronized int trackedPassageCount() {
        return passages.size();
    }

    private record Key(ReceiverReference receiver, String trainName) {
        private static Key create(ReceiverReference receiver, String trainName) {
            Objects.requireNonNull(receiver, "receiver");
            Objects.requireNonNull(trainName, "trainName");
            String normalizedName = trainName.strip();
            if (normalizedName.isEmpty()) {
                throw new IllegalArgumentException("Train name must not be blank");
            }
            return new Key(receiver, normalizedName);
        }
    }

    private record Passage(UUID id, boolean active, Instant changedAt) {
    }
}

