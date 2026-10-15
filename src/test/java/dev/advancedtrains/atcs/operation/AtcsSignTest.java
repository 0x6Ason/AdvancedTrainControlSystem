package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.control.Heading;
import dev.advancedtrains.atcs.control.StopOptions;
import dev.advancedtrains.atcs.control.SwitchRules;
import dev.advancedtrains.atcs.network.ServiceKey;
import org.junit.jupiter.api.Test;

import java.util.List;

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
    void parsesStationAndSwitchSigns() {
        AtcsSign.StationSign station = (AtcsSign.StationSign) AtcsSign.parse("atcs station", "Main:2", "10s reverse");
        assertEquals("main", station.stationId());
        assertEquals("2", station.platformId());
        assertEquals(10, station.options().dwellSeconds());
        assertEquals(Heading.REVERSE, station.options().heading());
        assertEquals("main:2", station.destinationName());
        assertEquals(new AtcsSign.StationSign("main", "1", StopOptions.DEFAULT), AtcsSign.parse("atcs station", "main", ""));
        AtcsSign.SwitchSign switcher = (AtcsSign.SwitchSign) AtcsSign.parse("atcs switch", "left 1/express", "", List.of("right *"));
        assertEquals(List.of(new SwitchRules.Rule(Heading.LEFT, List.of("1/express")), new SwitchRules.Rule(Heading.RIGHT, List.of("*"))),
                switcher.rules().rules());
        assertThrows(IllegalArgumentException.class, () -> AtcsSign.parse("atcs station", "main", "sideways"));
        assertThrows(IllegalArgumentException.class, () -> AtcsSign.parse("atcs switch", "", ""));
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
