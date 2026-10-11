package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.network.ServiceKey;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static dev.advancedtrains.atcs.TestNetworks.network;
import static dev.advancedtrains.atcs.TestNetworks.service;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteMatcherTest {
    private static final ServiceKey CLOCKWISE = ServiceKey.parse("loop/clockwise");
    private static final ServiceKey ANTICLOCKWISE = ServiceKey.parse("loop/anticlockwise");

    /** A three-station loop served in both directions plus a two-station shuttle sharing s1 and s2. */
    private final Network network = network(4,
            service("loop/clockwise", "s1", "s2", "s3").withCircular(true),
            service("loop/anticlockwise", "s3", "s2", "s1").withCircular(true),
            service("b/shuttle", "s1", "s2"),
            service("b/back", "s2", "s1"));

    @Test
    void twoStationsInOrderIdentifyTheDirection() {
        assertEquals(Optional.of(CLOCKWISE), RouteMatcher.infer(List.of("s2", "s3"), network, 2));
        assertEquals(Optional.of(ANTICLOCKWISE), RouteMatcher.infer(List.of("s3", "s2"), network, 2));
        // s2 -> s1 fits both the anticlockwise loop and the b/back shuttle.
        assertTrue(RouteMatcher.infer(List.of("s2", "s1"), network, 2).isEmpty());
    }

    @Test
    void wrapsAroundACircularService() {
        assertEquals(Optional.of(CLOCKWISE), RouteMatcher.infer(List.of("s3", "s1"), network, 2));
        assertEquals(Optional.of(ANTICLOCKWISE), RouteMatcher.infer(List.of("s1", "s3"), network, 2));
    }

    @Test
    void ambiguousPairsWaitForMoreVisits() {
        // s1 -> s2 fits the clockwise loop and the shuttle.
        assertTrue(RouteMatcher.infer(List.of("s1", "s2"), network, 2).isEmpty());
        assertEquals(Optional.of(CLOCKWISE), RouteMatcher.infer(List.of("s1", "s2", "s3"), network, 2));
        assertEquals(Optional.of(CLOCKWISE), RouteMatcher.infer(List.of("s3", "s1", "s2"), network, 2));
    }

    @Test
    void needsEnoughVisitsAndKnownStations() {
        assertTrue(RouteMatcher.infer(List.of("s2"), network, 2).isEmpty());
        assertTrue(RouteMatcher.infer(List.of(), network, 2).isEmpty());
        assertTrue(RouteMatcher.infer(List.of("s4", "s1"), network, 2).isEmpty(), "s4 is on no service");
    }

    @Test
    void onlyTheRecentWindowMatters() {
        // An old visit from another line does not block the inference once the recent visits are unique.
        assertEquals(Optional.of(ANTICLOCKWISE), RouteMatcher.infer(List.of("s4", "s3", "s2"), network, 2));
    }

    @Test
    void repeatedPlatformsOfOneStationCountOnce() {
        assertEquals(List.of("s1", "s2"), RouteMatcher.collapse(List.of("s1", "s1", "s2", "s2")));
        assertEquals(Optional.of(CLOCKWISE), RouteMatcher.infer(List.of("s2", "s2", "s3"), network, 2));
    }

    @Test
    void tripLogExposesRecentStations() {
        TripLog log = new TripLog();
        for (String station : List.of("s1", "s2", "s3")) {
            log.record(new JourneyEvent.UnassignedArrived("t", Instant.EPOCH, new PlatformRef(station, "1")));
        }
        assertEquals(List.of("s2", "s3"), log.recentStations("t", 2));
        assertEquals(List.of("s1", "s2", "s3"), log.recentStations("t", 8));
        assertEquals(List.of(), log.recentStations("unknown", 8));
    }
}
