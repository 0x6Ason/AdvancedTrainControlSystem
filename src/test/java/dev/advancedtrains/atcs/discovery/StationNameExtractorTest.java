package dev.advancedtrains.atcs.discovery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StationNameExtractorTest {
    private final StationNameExtractor extractor = new StationNameExtractor(
            List.of("Arrived:", "Arrive:"));

    @Test
    void joinsWrappedArrivalTextWithoutChangingTheName() {
        assertEquals("Middle of Nowhere", extractor.extract(
                "Arrive:  \nMiddle of Nowhere").orElseThrow());
    }

    @Test
    void ignoresDestinationAndNextStationAnnouncements() {
        assertTrue(extractor.extract("To: Central Station").isEmpty());
        assertTrue(extractor.extract("Next: Central Station").isEmpty());
    }
}
