package dev.advancedtrains.atcs.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StopOptionsTest {

    @Test
    void emptyLineMeansTheDefaults() {
        StopOptions options = StopOptions.parse("  ");
        assertEquals(StopOptions.DEFAULT, options);
        assertFalse(options.hasDwell());
        assertFalse(options.hasHeadway());
        assertNull(options.holdForSection());
        assertEquals(Heading.CONTINUE, options.heading());
    }

    @Test
    void readsTheTrainCartsStyleLine() {
        StopOptions options = StopOptions.parse("10s continue");
        assertEquals(10, options.dwellSeconds());
        assertEquals(Heading.CONTINUE, options.heading());
        assertEquals(Heading.REVERSE, StopOptions.parse("20 reverse").heading());
        assertEquals(20, StopOptions.parse("20 reverse").dwellSeconds());
        assertEquals(Heading.LEFT, StopOptions.parse("left 5").heading());
        assertEquals(Heading.NORTH, StopOptions.parse("n").heading());
        assertTrue(StopOptions.parse("route").autoRoute());
        assertEquals(2.5, StopOptions.parse("2.5m 30b").offset());
        assertEquals(30, StopOptions.parse("2.5m 30b").launchDistance());
        assertEquals(-1.5, StopOptions.parse("-1.5m").offset());
    }

    @Test
    void readsHeadwayAndSectionWords() {
        StopOptions options = StopOptions.parse("gap 90s block");
        assertEquals(90, options.headwaySeconds());
        assertEquals(Boolean.TRUE, options.holdForSection());
        assertEquals(60, StopOptions.parse("gap60").headwaySeconds());
        assertEquals(Boolean.FALSE, StopOptions.parse("noblock").holdForSection());
        assertEquals(0, StopOptions.parse("gap 0").headwaySeconds(), "gap 0 switches the configured headway off");
        assertTrue(StopOptions.parse("gap 0").hasHeadway());
    }

    @Test
    void readsSpacingAndTimetableWords() {
        StopOptions options = StopOptions.parse("even at :00 :30");
        assertEquals(Boolean.TRUE, options.evenSpacing());
        assertEquals(java.util.List.of(0, 30), options.departureMinutes());
        assertTrue(options.hasTimetable());
        assertEquals(Boolean.FALSE, StopOptions.parse("noeven").evenSpacing());
        assertNull(StopOptions.parse("10s").evenSpacing());
        assertEquals(java.util.List.of(0, 20, 40), StopOptions.parse("at /20 reverse").departureMinutes());
        assertEquals(java.util.List.of(5, 35), StopOptions.parse("at :35 :05 10s").departureMinutes(), "sorted, and the dwell still reads");
        assertEquals(10, StopOptions.parse("at :35 :05 10s").dwellSeconds());
        assertThrows(IllegalArgumentException.class, () -> StopOptions.parse("at"));
        assertThrows(IllegalArgumentException.class, () -> StopOptions.parse("at :75"));
        assertThrows(IllegalArgumentException.class, () -> StopOptions.parse("at /0"));
        assertTrue(StopOptions.parse("even at :00").describe().contains("depart at :00 past the hour"));
    }

    @Test
    void rejectsWhatItCannotRead() {
        assertThrows(IllegalArgumentException.class, () -> StopOptions.parse("sideways"));
        assertThrows(IllegalArgumentException.class, () -> StopOptions.parse("10 20"));
        assertThrows(IllegalArgumentException.class, () -> StopOptions.parse("left right"));
        assertThrows(IllegalArgumentException.class, () -> StopOptions.parse("gap"));
        assertThrows(IllegalArgumentException.class, () -> StopOptions.parse("gap left"));
        assertThrows(IllegalArgumentException.class, () -> StopOptions.parse("0b"));
    }

    @Test
    void describesItselfForTheSignBuilder() {
        assertEquals("stop the trains that call here for 10 s, then send them on", StopOptions.parse("10s").describe());
        assertEquals("stop the trains that call here, then send them reverse; headway 90 s; wait for a clear section ahead",
                StopOptions.parse("reverse gap 90 block").describe());
        assertEquals("stop the trains that call here for 5 s, then let TrainCarts route them, centred 2.5 blocks past the sign,"
                + " launching over 30 blocks, launching at 0.4 blocks per tick", StopOptions.parse("5 route 2.5m 30b 0.4v").describe());
        assertEquals(0.4, StopOptions.parse("0.4v").launchSpeed());
        assertThrows(IllegalArgumentException.class, () -> StopOptions.parse("0v"));
    }
}
