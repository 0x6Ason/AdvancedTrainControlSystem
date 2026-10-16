package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.network.Stop;
import dev.advancedtrains.atcs.network.StopEdits;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import static dev.advancedtrains.atcs.TestNetworks.network;
import static dev.advancedtrains.atcs.TestNetworks.service;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemapTest {
    private static final Instant T0 = Instant.parse("2026-10-15T10:00:00Z");
    private static final ServiceKey KEY = ServiceKey.parse("a/north");

    @Test
    void insertingAStopKeepsWhatWasLearnedAboutTheOthers() {
        Service before = service("a/north", "s1", "s2", "s4");
        Network network = network(4, before);
        TravelTimes times = new TravelTimes(0.3);
        PlatformRef p1 = new PlatformRef("s1", "1");
        PlatformRef p2 = new PlatformRef("s2", "2");
        PlatformRef p4 = new PlatformRef("s4", "1");
        times.record(new JourneyEvent.Arrived("t", T0, KEY, 0, p1, false, -1, null), network);
        times.record(new JourneyEvent.Departed("t", T0.plusSeconds(12), KEY, 0, p1, 1, Duration.ofSeconds(12)), network);
        times.record(new JourneyEvent.Arrived("t", T0.plusSeconds(60), KEY, 1, p2, false, 0, Duration.ofSeconds(48)), network);
        times.record(new JourneyEvent.Departed("t", T0.plusSeconds(70), KEY, 1, p2, 2, Duration.ofSeconds(10)), network);
        times.record(new JourneyEvent.Arrived("t", T0.plusSeconds(130), KEY, 2, p4, true, 1, Duration.ofSeconds(60)), network);
        assertEquals(2, times.learnedSegments(before));

        Service after = before.withStops(StopEdits.insertBetween(before.stops(), Stop.parse("s3"), "s2", "s4", false));
        times.remap(before, after);
        assertEquals(OptionalDouble.of(48), times.segmentSeconds(after, 0), "s1 to s2 is unchanged");
        assertEquals(OptionalDouble.empty(), times.segmentSeconds(after, 1), "s2 to s3 is new");
        assertEquals(OptionalDouble.empty(), times.segmentSeconds(after, 2), "s3 to s4 is new");
        assertEquals(OptionalDouble.of(12), times.dwellSeconds(after, 0));
        assertEquals(OptionalDouble.of(10), times.dwellSeconds(after, 1));
        assertEquals(Optional.of("2"), times.usualPlatform(after, 1));
        assertEquals(Optional.of("1"), times.usualPlatform(after, 3));
        assertEquals(1, times.learnedSegments(after));
        assertTrue(times.dirty());

        Service shorter = after.withStops(StopEdits.remove(after.stops(), "s3"));
        times.remap(after, shorter);
        assertEquals(OptionalDouble.of(48), times.segmentSeconds(shorter, 0));
        assertEquals(OptionalDouble.empty(), times.ownSegmentSeconds(shorter, 1), "s2 to s4 was not learned while s3 was listed");
        assertEquals(OptionalDouble.of(60), times.segmentSeconds(shorter, 1), "but the stretch itself is remembered");
        assertEquals(Optional.of("1"), times.usualPlatform(shorter, 2));
    }

    @Test
    void recordedPathsFollowTheSameRule() {
        Service before = service("a/north", "s1", "s2", "s4");
        Network network = network(4, before);
        TrackPaths paths = new TrackPaths();
        PlatformRef p1 = new PlatformRef("s1", "1");
        PlatformRef p2 = new PlatformRef("s2", "1");
        paths.onEvent(new JourneyEvent.Departed("t", T0, KEY, 0, p1, 1, null), network);
        paths.sample("t", "world", 100, 64, 0);
        paths.sample("t", "world", 150, 64, 0);
        paths.sample("t", "world", 200, 64, 0);
        paths.onEvent(new JourneyEvent.Arrived("t", T0.plusSeconds(30), KEY, 1, p2, false, 0, Duration.ofSeconds(30)), network);
        assertEquals(1, paths.recordedSegments(before));

        Service after = before.withStops(List.of(Stop.parse("s1"), Stop.parse("s2"), Stop.parse("s3"), Stop.parse("s4")));
        paths.remap(before, after);
        assertEquals(1, paths.recordedSegments(after));
        assertTrue(paths.path(after, 0).isPresent());
        assertTrue(paths.path(after, 1).isEmpty());
    }
}
