package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static dev.advancedtrains.atcs.TestNetworks.network;
import static dev.advancedtrains.atcs.TestNetworks.platform;
import static dev.advancedtrains.atcs.TestNetworks.service;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackPathsTest {
    private static final ServiceKey A = ServiceKey.parse("a/north");
    private static final Instant T0 = Instant.EPOCH;

    private final Network network = network(3, service("a/north", "s1", "s2", "s3"));
    private final Service north = network.service(A).orElseThrow();
    private final TrackPaths paths = new TrackPaths();

    private void runSegment(String train, int from) {
        paths.onEvent(new JourneyEvent.Departed(train, T0, A, from, platform("s" + (from + 1)), from + 1,
                Duration.ofSeconds(5)), network);
        assertTrue(paths.isRecording(train));
        for (int step = 0; step <= 100; step++) {
            // An L-shaped track: east, then north.
            double x = step <= 50 ? step : 50;
            double z = step <= 50 ? 0 : step - 50;
            paths.sample(train, "world", x + 0.5, 64, z + 0.5);
            if (step == 80) {
                // The runtime announces the next stop while the train is still on the segment.
                paths.onEvent(new JourneyEvent.Approaching(train, T0, A, from + 1), network);
            }
        }
        paths.onEvent(new JourneyEvent.Arrived(train, T0, A, from + 1, platform("s" + (from + 2)), false, from,
                Duration.ofSeconds(30)), network);
    }

    @Test
    void recordsAndSimplifiesASegment() {
        runSegment("t1", 0);
        TrackPaths.Path path = paths.path(north, 0).orElseThrow();
        assertEquals("world", path.world());
        assertEquals(3, path.points().size(), "a straight run and a corner keep only the corner: " + path.points());
        assertTrue(paths.dirty());
        assertEquals(1, paths.recordedSegments(north));
    }

    @Test
    void keepsTheFirstRecordingAndRoundTrips() {
        runSegment("t1", 0);
        int version = paths.version();
        paths.onEvent(new JourneyEvent.Departed("t2", T0, A, 0, platform("s1"), 1, null), network);
        assertFalse(paths.isRecording("t2"), "already recorded");

        YamlConfiguration yaml = new YamlConfiguration();
        paths.save(yaml);
        TrackPaths loaded = new TrackPaths();
        loaded.load(yaml);
        assertEquals(paths.path(north, 0), loaded.path(north, 0));
        assertEquals(version, paths.version());
    }

    @Test
    void abandonsRecordingWhenTheTripChanges() {
        paths.onEvent(new JourneyEvent.Departed("t1", T0, A, 0, platform("s1"), 1, null), network);
        paths.onEvent(new JourneyEvent.ServiceChanged("t1", T0, A, null), network);
        assertFalse(paths.isRecording("t1"));
        assertTrue(paths.path(north, 0).isEmpty());
    }

    @Test
    void simplificationKeepsCorners() {
        List<TrackPaths.Point> square = new ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            square.add(new TrackPaths.Point(i, 0, 0));
        }
        for (int i = 1; i <= 10; i++) {
            square.add(new TrackPaths.Point(10, 0, i));
        }
        assertEquals(List.of(new TrackPaths.Point(0, 0, 0), new TrackPaths.Point(10, 0, 0), new TrackPaths.Point(10, 0, 10)),
                TrackPaths.simplify(square, TrackPaths.TOLERANCE));
    }
}
