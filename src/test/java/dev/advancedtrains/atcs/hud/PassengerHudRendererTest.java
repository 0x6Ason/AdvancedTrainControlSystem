package dev.advancedtrains.atcs.hud;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PassengerHudRendererTest {
    @Test
    void rendersTheContinuousPassengerInformationStandard() {
        PassengerHudSnapshot snapshot = new PassengerHudSnapshot(
                "Central Line", "Express", "Airport", "Harbour", "In service");

        String text = PlainTextComponentSerializer.plainText().serialize(
                new PassengerHudRenderer().render(snapshot));

        assertEquals(
                "Central Line Express  ◆  To: Airport  ◆  Next: Harbour  ◆  In service",
                text);
    }
}

