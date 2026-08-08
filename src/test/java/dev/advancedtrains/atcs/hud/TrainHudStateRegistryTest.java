package dev.advancedtrains.atcs.hud;

import dev.advancedtrains.atcs.event.OperationalEventType;
import dev.advancedtrains.atcs.receiver.ReceiverReference;
import dev.advancedtrains.atcs.receiver.ReceiverTransition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainHudStateRegistryTest {
    @Test
    void transitionsFromApproachToDepartureAndThenExpires() {
        TrainHudStateRegistry registry = new TrainHudStateRegistry();
        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        ReceiverReference receiver = ReceiverReference.parse("CEN:P1");
        UUID passage = UUID.randomUUID();

        registry.observe(new ReceiverTransition(
                receiver, "RC-LP-0001", passage, OperationalEventType.RECEIVER_ENTERING, now));
        assertEquals(
                "Approaching: CEN (Platform P1)",
                registry.statusFor("RC-LP-0001", now).orElseThrow());

        registry.observe(new ReceiverTransition(
                receiver, "RC-LP-0001", passage, OperationalEventType.RECEIVER_CLEARED, now.plusSeconds(5)));
        assertEquals(
                "Departed: CEN (Platform P1)",
                registry.statusFor("RC-LP-0001", now.plusSeconds(6)).orElseThrow());
        assertTrue(registry.statusFor("RC-LP-0001", now.plusSeconds(30)).isEmpty());
    }
}

