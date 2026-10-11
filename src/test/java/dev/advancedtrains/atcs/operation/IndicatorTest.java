package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.runtime.IndicatorService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static dev.advancedtrains.atcs.TestNetworks.network;
import static dev.advancedtrains.atcs.TestNetworks.service;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndicatorTest {
    private static final ServiceKey NORTH = ServiceKey.parse("a/north");
    private static final ServiceKey SOUTH = ServiceKey.parse("a/south");
    private static final ServiceKey LOOP = ServiceKey.parse("5/cw");
    private static final Instant T0 = Instant.parse("2026-10-11T12:00:00Z");

    private final Network network = network(3,
            service("a/north", "s1:1", "s2:1", "s3:1"),
            service("a/south", "s3:2", "s2:2", "s1:2"),
            service("5/cw", "s1", "s2", "s3").withCircular(true));

    @Test
    void parsesStationPlatformModeAndFilter() {
        assertEquals(new Indicator(Indicator.Mode.AT, "main", null, null, null, null), Indicator.parse("Main", ""));
        assertEquals(new Indicator(Indicator.Mode.AT, "main", "2", null, null, null), Indicator.parse("main:2", "at"));
        assertEquals(new Indicator(Indicator.Mode.APPROACH, "main", null, null, "a", null), Indicator.parse("main", "approach a"));
        assertEquals(new Indicator(Indicator.Mode.AFTER, "main", "1", null, null, NORTH), Indicator.parse("main:1", "after a/north"));
        assertEquals(new Indicator(Indicator.Mode.AT, "main", null, null, null, LOOP), Indicator.parse("main", "5/cw"),
                "a filter alone keeps the default mode");
        assertThrows(IllegalArgumentException.class, () -> Indicator.parse("", "at"));
        assertThrows(IllegalArgumentException.class, () -> Indicator.parse("main", "at a extra"));
        assertThrows(IllegalArgumentException.class, () -> Indicator.parse("bad id!", ""));
        assertInstanceOfIndicatorSign();
    }

    private static void assertInstanceOfIndicatorSign() {
        AtcsSign sign = AtcsSign.parse("atcs indicator", "main:2", "approach 5/cw");
        assertEquals(new AtcsSign.IndicatorSign(new Indicator(Indicator.Mode.APPROACH, "main", "2", null, null, LOOP)), sign);
    }

    @Test
    void lightsWhileATrainStandsAtTheStationOrPlatform() {
        JourneySnapshot standing = at("t1", NORTH, 1, platform("s2", "1"));
        JourneySnapshot elsewhere = at("t2", SOUTH, 1, platform("s1", "2"));
        assertTrue(Indicator.parse("s2", "").active(List.of(standing, elsewhere), network));
        assertTrue(Indicator.parse("s2:1", "").active(List.of(standing), network));
        assertFalse(Indicator.parse("s2:2", "").active(List.of(standing), network), "other platform");
        assertFalse(Indicator.parse("s2", "at a/south").active(List.of(standing), network), "other service");
        assertTrue(Indicator.parse("s2", "at a").active(List.of(standing), network), "same line");
        assertTrue(Indicator.parse("s1", "").active(List.of(at("t3", null, -1, platform("s1", "1"))), network),
                "trains without a service still count");
        assertFalse(Indicator.parse("s1", "a").active(List.of(at("t3", null, -1, platform("s1", "1"))), network),
                "unless a line is named");
    }

    @Test
    void lightsAfterDepartureUntilTheNextStopAndWhileApproaching() {
        JourneySnapshot running = new JourneySnapshot("t1", NORTH, JourneyPhase.IN_TRANSIT, 0, null,
                platform("s1", "1"), -1, T0, T0, T0, T0);
        assertTrue(Indicator.parse("s1", "after").active(List.of(running), network));
        assertTrue(Indicator.parse("s1:1", "after").active(List.of(running), network));
        assertFalse(Indicator.parse("s1:2", "after").active(List.of(running), network));
        assertFalse(Indicator.parse("s1", "").active(List.of(running), network), "not standing there any more");
        assertFalse(Indicator.parse("s2", "approach").active(List.of(running), network), "not announced yet");

        JourneySnapshot approaching = new JourneySnapshot("t1", NORTH, JourneyPhase.IN_TRANSIT, 0, null,
                platform("s1", "1"), 1, T0, T0, T0, T0);
        assertTrue(Indicator.parse("s2", "approach").active(List.of(approaching), network));
        assertTrue(Indicator.parse("s2:1", "approach").active(List.of(approaching), network), "planned platform");
        assertFalse(Indicator.parse("s2:2", "approach").active(List.of(approaching), network));
        assertFalse(Indicator.parse("s3", "approach").active(List.of(approaching), network));

        JourneySnapshot arrived = new JourneySnapshot("t1", NORTH, JourneyPhase.AT_STOP, 1, platform("s2", "1"),
                platform("s2", "1"), 1, T0, T0, T0, T0);
        assertFalse(Indicator.parse("s2", "approach").active(List.of(arrived), network), "arrived: approach ends");
        assertFalse(Indicator.parse("s1", "after").active(List.of(arrived), network));
        assertTrue(Indicator.parse("s2", "").active(List.of(arrived), network));
    }

    @Test
    void lightsBetweenTwoStationsInOneOrBothDirections() {
        assertEquals(new Indicator(Indicator.Mode.TO, "s1", null, "s2", null, null), Indicator.parse("s1", "to s2"));
        assertEquals(new Indicator(Indicator.Mode.BETWEEN, "s1", "1", "s2", "a", null), Indicator.parse("s1:1", "between s2 a"));
        assertThrows(IllegalArgumentException.class, () -> Indicator.parse("s1", "to"));
        assertThrows(IllegalArgumentException.class, () -> Indicator.parse("s1", "between s1"));

        JourneySnapshot northbound = new JourneySnapshot("t1", NORTH, JourneyPhase.IN_TRANSIT, 0, null,
                platform("s1", "1"), -1, T0, T0, T0, T0);
        JourneySnapshot southbound = new JourneySnapshot("t2", SOUTH, JourneyPhase.IN_TRANSIT, 1, null,
                platform("s2", "2"), -1, T0, T0, T0, T0);
        assertTrue(Indicator.parse("s1", "to s2").active(List.of(northbound), network));
        assertFalse(Indicator.parse("s1", "to s3").active(List.of(northbound), network), "next stop is s2");
        assertFalse(Indicator.parse("s1", "to s2").active(List.of(southbound), network), "wrong direction");
        assertTrue(Indicator.parse("s1", "between s2").active(List.of(southbound), network), "either direction");
        assertTrue(Indicator.parse("s2", "between s1").active(List.of(northbound), network));
        assertFalse(Indicator.parse("s1:2", "to s2").active(List.of(northbound), network), "left from platform 1");
        assertFalse(Indicator.parse("s2", "to s3").active(List.of(southbound), network));
        JourneySnapshot arrived = new JourneySnapshot("t1", NORTH, JourneyPhase.AT_STOP, 1, platform("s2", "1"),
                platform("s2", "1"), -1, T0, T0, T0, T0);
        assertFalse(Indicator.parse("s1", "to s2").active(List.of(arrived), network), "arrived: off");
    }

    @Test
    void serviceOnlySwitchesOutputsThatChange() {
        List<String> switched = new ArrayList<>();
        IndicatorService indicators = new IndicatorService((sign, powered) -> switched.add(sign.coordinates() + "=" + powered));
        BlockPos lamp = new BlockPos("world", 1, 64, 1);
        indicators.register(lamp, Indicator.parse("s2", ""));
        indicators.refresh(List.of(), network);
        assertEquals(List.of("1 64 1=false"), switched, "first refresh sets the output");
        indicators.refresh(List.of(), network);
        assertEquals(1, switched.size(), "unchanged: untouched");
        indicators.refresh(List.of(at("t1", NORTH, 1, platform("s2", "1"))), network);
        assertEquals(List.of("1 64 1=false", "1 64 1=true"), switched);
        assertEquals(1, indicators.lit());
        indicators.shutdown();
        assertEquals("1 64 1=false", switched.get(2), "shutdown clears lit outputs");
        assertEquals(0, indicators.size());
    }

    private static JourneySnapshot at(String train, ServiceKey service, int index, PlatformRef platform) {
        return new JourneySnapshot(train, service, JourneyPhase.AT_STOP, index, platform, T0, null, null, T0);
    }

    private static PlatformRef platform(String station, String platform) {
        return new PlatformRef(station, platform);
    }
}
