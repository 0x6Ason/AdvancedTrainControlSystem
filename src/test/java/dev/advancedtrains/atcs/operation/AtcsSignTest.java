package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.ServiceKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AtcsSignTest {

    @Test
    void parsesStopSigns() {
        assertEquals(new AtcsSign.StopSign("main", "1"), AtcsSign.parse("atcs stop", "main", ""));
        assertEquals(new AtcsSign.StopSign("main", "2"), AtcsSign.parse("atcs stop", "Main", "2"));
        assertEquals(new AtcsSign.StopSign("main", "east"), AtcsSign.parse("ATCS platform", "main:east", "ignored"));
    }

    @Test
    void parsesServiceAndClearSigns() {
        assertEquals(new AtcsSign.ServiceSign(ServiceKey.parse("a/north")), AtcsSign.parse("atcs service", "a/north", ""));
        assertEquals(new AtcsSign.ServiceSign(ServiceKey.parse("a/north")), AtcsSign.parse("atcs assign", "a", "north"));
        assertInstanceOf(AtcsSign.ClearSign.class, AtcsSign.parse("atcs clear", "", ""));
    }

    @Test
    void rejectsBadTextWithReadableMessages() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> AtcsSign.parse("atcs stop", "", ""));
        assertEquals("Line 3 must name the station, for example 'main' or 'main:2'", missing.getMessage());
        assertThrows(IllegalArgumentException.class, () -> AtcsSign.parse("atcs dance", "", ""));
        assertThrows(IllegalArgumentException.class, () -> AtcsSign.parse("station", "main", ""));
        assertThrows(IllegalArgumentException.class, () -> AtcsSign.parse("atcs service", "no-slash", ""));
        assertThrows(IllegalArgumentException.class, () -> AtcsSign.parse("atcs stop", "bad id!", ""));
    }
}
