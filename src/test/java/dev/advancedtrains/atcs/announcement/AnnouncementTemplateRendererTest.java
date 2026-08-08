package dev.advancedtrains.atcs.announcement;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnnouncementTemplateRendererTest {
    private final AnnouncementTemplateRenderer renderer = new AnnouncementTemplateRenderer();

    @Test
    void rendersKnownVariablesLiterally() {
        String rendered = renderer.renderStrict(
                "The next station is {next_station}. This train is for {destination}.",
                Map.of("next_station", "Harbour", "destination", "$Airport\\Terminal"));
        assertEquals("The next station is Harbour. This train is for $Airport\\Terminal.", rendered);
    }

    @Test
    void strictRenderingRejectsMissingOperationalData() {
        assertThrows(IllegalArgumentException.class,
                () -> renderer.renderStrict("Delayed by {delay} at {station}.", Map.of("station", "Central")));
    }

    @Test
    void lenientRenderingDoesNotLeakPlaceholdersToPassengers() {
        assertEquals("Delayed by at Central.",
                renderer.renderLenient("Delayed by {delay} at {station}.", Map.of("station", "Central")));
    }
}

