package dev.advancedtrains.atcs.receiver;

import dev.advancedtrains.atcs.event.OperationalEventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceiverStateTrackerTest {
    private static final ReceiverReference RECEIVER = ReceiverReference.parse("CEN:P1");
    private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");

    @Test
    void suppressesRepeatedCallbacksWithinOnePassage() {
        ReceiverStateTracker tracker = new ReceiverStateTracker();

        ReceiverTransition entering = tracker.enter(RECEIVER, "RC-LP-0001", NOW).orElseThrow();
        assertEquals(OperationalEventType.RECEIVER_ENTERING, entering.eventType());
        assertTrue(tracker.enter(RECEIVER, "RC-LP-0001", NOW).isEmpty());

        ReceiverTransition cleared = tracker.leave(RECEIVER, "RC-LP-0001", NOW).orElseThrow();
        assertEquals(entering.passageId(), cleared.passageId());
        assertEquals(OperationalEventType.RECEIVER_CLEARED, cleared.eventType());
        assertTrue(tracker.leave(RECEIVER, "RC-LP-0001", NOW).isEmpty());

        ReceiverTransition nextPassage = tracker.enter(RECEIVER, "RC-LP-0001", NOW).orElseThrow();
        assertTrue(!entering.passageId().equals(nextPassage.passageId()));
    }
}

