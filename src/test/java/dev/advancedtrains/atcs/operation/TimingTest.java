package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.network.Stop;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static dev.advancedtrains.atcs.TestNetworks.network;
import static dev.advancedtrains.atcs.TestNetworks.platform;
import static dev.advancedtrains.atcs.TestNetworks.service;
import static dev.advancedtrains.atcs.TestNetworks.trigger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimingTest {
    private static final ServiceKey A = ServiceKey.parse("a/north");
    private static final Instant T0 = Instant.parse("2026-10-11T12:00:00Z");

    private final Network network = network(4, service("a/north", "s1", "s2", "~s3", "s4"));
    private final Service north = network.service(A).orElseThrow();
    private final TravelTimes times = new TravelTimes(0.5);
    private final EtaCalculator eta = new EtaCalculator(times, 10);

    private void learn(int fromIndex, int seconds) {
        times.record(new JourneyEvent.Arrived("t", T0, A, fromIndex + 1, platform("s" + (fromIndex + 2)),
                false, fromIndex, Duration.ofSeconds(seconds)), network);
    }

    private void learnDwell(int index, int seconds) {
        times.record(new JourneyEvent.Departed("t", T0, A, index, platform("s" + (index + 1)), index + 1,
                Duration.ofSeconds(seconds)), network);
    }

    @Test
    void smoothsSamplesAndDampensOutliers() {
        learn(0, 30);
        assertEquals(30, times.segmentSeconds(north, 0).orElseThrow(), 1e-9);
        learn(0, 40);
        assertEquals(35, times.segmentSeconds(north, 0).orElseThrow(), 1e-9);
        learn(0, 35);
        learn(0, 35);
        double before = times.segmentSeconds(north, 0).orElseThrow();
        learn(0, 600);
        double after = times.segmentSeconds(north, 0).orElseThrow();
        assertTrue(after - before < 60, "a train held at a signal must not dominate: " + after);
    }

    @Test
    void forgetsTimingsWhenStopsChange() {
        learn(0, 30);
        Network changed = network.toBuilder()
                .line(network.line("a").orElseThrow().withService(north.withStops(
                        List.of(Stop.of("s1"), Stop.of("s4")))))
                .build();
        Service shortened = changed.service(A).orElseThrow();
        assertTrue(times.segmentSeconds(shortened, 0).isEmpty());
    }

    @Test
    void roundTripsThroughYaml() {
        learn(0, 30);
        learnDwell(1, 8);
        times.record(new JourneyEvent.Arrived("t", T0, A, 1, new PlatformRef("s2", "2"), false, -1, null), network);
        YamlConfiguration yaml = new YamlConfiguration();
        times.save(yaml);
        assertTrue(!times.dirty());

        TravelTimes loaded = new TravelTimes(0.5);
        loaded.load(yaml);
        assertEquals(30, loaded.segmentSeconds(north, 0).orElseThrow(), 1e-9);
        assertEquals(8, loaded.dwellSeconds(north, 1).orElseThrow(), 1e-9);
        assertEquals("2", loaded.usualPlatform(north, 1).orElseThrow());
    }

    @Test
    void estimatesArrivalWhileRunningAndStanding() {
        learn(0, 30);
        learnDwell(1, 8);
        learn(1, 20);
        learn(2, 15);
        // Running from s1 for 10 s.
        JourneySnapshot running = new JourneySnapshot("t", A, JourneyPhase.IN_TRANSIT, 0, null,
                T0, T0, T0, T0);
        Instant now = T0.plusSeconds(10);
        assertEquals(20, eta.secondsUntil(running, north, 1, now).orElseThrow(), 1e-9);
        // s4: 20 left + 8 dwell at s2 + 20 to s3 + 0 dwell passing s3 + 15 to s4.
        assertEquals(63, eta.secondsUntil(running, north, 3, now).orElseThrow(), 1e-9);

        // Standing at s2 for 3 s of an 8 s dwell.
        JourneySnapshot standing = new JourneySnapshot("t", A, JourneyPhase.AT_STOP, 1, platform("s2"),
                T0, null, null, T0);
        assertEquals(0, eta.secondsUntil(standing, north, 1, T0.plusSeconds(3)).orElseThrow(), 1e-9);
        assertEquals(5 + 20 + 15, eta.secondsUntil(standing, north, 3, T0.plusSeconds(3)).orElseThrow(), 1e-9);
    }

    @Test
    void unknownSegmentMeansUnknownArrival() {
        learn(0, 30);
        JourneySnapshot running = new JourneySnapshot("t", A, JourneyPhase.IN_TRANSIT, 0, null, T0, T0, T0, T0);
        assertTrue(eta.secondsUntil(running, north, 3, T0).isEmpty());
        JourneySnapshot idle = new JourneySnapshot("t", A, JourneyPhase.IDLE, -1, null, null, null, null, T0);
        assertTrue(eta.secondsUntil(idle, north, 1, T0).isEmpty());
    }

    @Test
    void listsDeparturesSoonestFirst() {
        learn(0, 30);
        learn(1, 20);
        learnDwell(1, 8);
        JourneyTracker tracker = new JourneyTracker();
        tracker.observe(StopObservation.enter("boarding", platform("s2"), trigger("s2"), T0), A, network);
        tracker.observe(StopObservation.enter("coming", platform("s1"), trigger("s1"), T0), A, network);
        tracker.observe(StopObservation.leave("coming", platform("s1"), trigger("s1"), T0), A, network);
        tracker.observe(StopObservation.enter("finished", platform("s4"), trigger("s4"), T0), A, network);

        List<Departures.Departure> fromS2 = Departures.from("s2", platform -> true, tracker.snapshots(),
                network, times, eta, T0.plusSeconds(5));
        assertEquals(List.of("boarding", "coming"), fromS2.stream().map(Departures.Departure::trainId).toList());
        assertEquals(Departures.Status.BOARDING, fromS2.get(0).status());
        assertEquals(25, fromS2.get(1).etaSeconds().orElseThrow(), 1e-9);

        assertTrue(Departures.from("s4", platform -> true, tracker.snapshots(), network, times, eta, T0).isEmpty(),
                "trains terminating at s4 are not departures from it");
        assertTrue(Departures.from("s2", "2"::equals, tracker.snapshots(), network, times, eta, T0).isEmpty(),
                "platform 1 departures are filtered out of a platform 2 board");
    }

    @Test
    void atATerminusDeparturesShowTheContinuingService() {
        Network turning = network(3,
                service("a/north", "s1", "s2", "s3").withContinuesAs(ServiceKey.parse("a/south")),
                service("a/south", "s3", "s2", "s1"));
        JourneyTracker tracker = new JourneyTracker();
        tracker.observe(StopObservation.enter("t", platform("s3"), trigger("s3"), T0), A, turning);
        List<Departures.Departure> fromS3 = Departures.from("s3", platform -> true, tracker.snapshots(),
                turning, times, eta, T0);
        assertEquals(1, fromS3.size());
        assertEquals(ServiceKey.parse("a/south"), fromS3.get(0).service().key());
        assertEquals(0, fromS3.get(0).stopIndex());
        assertEquals(Departures.Status.BOARDING, fromS3.get(0).status());

        Network ending = network(3, service("a/north", "s1", "s2", "s3"));
        JourneyTracker other = new JourneyTracker();
        other.observe(StopObservation.enter("t", platform("s3"), trigger("s3"), T0), A, ending);
        assertTrue(Departures.from("s3", platform -> true, other.snapshots(), ending, times, eta, T0).isEmpty(),
                "a train that terminates here is not a departure");
    }

    @Test
    void staleStopIndexesNeverCrashBoards() {
        JourneySnapshot stale = new JourneySnapshot("t", A, JourneyPhase.AT_STOP, 9, platform("s2"), T0, null, null, T0);
        assertTrue(eta.secondsUntil(stale, north, 1, T0).isEmpty());
        List<Departures.Departure> departures = Departures.from("s2", platform -> true, List.of(stale),
                network, times, eta, T0);
        assertEquals(1, departures.size());
        assertTrue(departures.get(0).etaSeconds().isEmpty());
    }
}
