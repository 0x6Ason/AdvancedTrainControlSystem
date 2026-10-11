package dev.advancedtrains.atcs.map;

import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.network.Station;
import dev.advancedtrains.atcs.network.Stop;
import dev.advancedtrains.atcs.operation.JourneyEvent;
import dev.advancedtrains.atcs.operation.TrackPaths;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static dev.advancedtrains.atcs.TestNetworks.network;
import static dev.advancedtrains.atcs.TestNetworks.platform;
import static dev.advancedtrains.atcs.TestNetworks.service;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapGeometryTest {
    private static final Instant T0 = Instant.EPOCH;

    private static final StationLocator LOCATOR = new StationLocator() {
        @Override
        public Optional<BlockPos> station(Station station) {
            return station.markerPosition();
        }

        @Override
        public Optional<BlockPos> stop(Station station, Stop stop) {
            return station.markerPosition();
        }
    };

    /** Records a path for segment {@code from} of a service, offset sideways by {@code track} blocks. */
    private static void record(TrackPaths paths, Network network, ServiceKey key, int from, double track) {
        Service service = network.service(key).orElseThrow();
        String train = key + "-" + from;
        paths.onEvent(new JourneyEvent.Departed(train, T0, key, from, platform(service.stop(from).stationId()),
                from + 1, Duration.ofSeconds(5)), network);
        for (int step = 0; step <= 100; step++) {
            paths.sample(train, "world", 100 + step, 64, track);
        }
        paths.onEvent(new JourneyEvent.Arrived(train, T0, key, from + 1,
                platform(service.stop(from + 1).stationId()), false, from, Duration.ofSeconds(30)), network);
    }

    @Test
    void unrecordedPairsAreOneStraightLineEachWay() {
        Network network = network(3, service("a/north", "s1", "s2", "s3"), service("a/south", "s3", "s2", "s1"));
        List<MapGeometry.Segment> segments = MapGeometry.segments(network, new TrackPaths(), LOCATOR);
        assertEquals(2, segments.size(), segments::toString);
        assertTrue(segments.stream().noneMatch(MapGeometry.Segment::recorded));
    }

    @Test
    void eachDirectionKeepsItsOwnRecordedTrack() {
        Network network = network(3, service("a/north", "s1", "s2", "s3"), service("a/south", "s3", "s2", "s1"));
        TrackPaths paths = new TrackPaths();
        record(paths, network, ServiceKey.parse("a/north"), 0, 0);
        record(paths, network, ServiceKey.parse("a/south"), 1, 4);
        List<MapGeometry.Segment> segments = MapGeometry.segments(network, paths, LOCATOR);
        long recorded = segments.stream().filter(MapGeometry.Segment::recorded).count();
        assertEquals(2, recorded, "both directions between s1 and s2 are drawn: " + segments);
        assertEquals(3, segments.size(), "plus one straight line for the s2-s3 pair");
    }

    @Test
    void identicalPathsOfTwoServicesAreDrawnOnce() {
        Network network = network(3, service("a/local", "s1", "s2", "s3"), service("a/express", "s1", "s2", "s3"));
        TrackPaths paths = new TrackPaths();
        record(paths, network, ServiceKey.parse("a/local"), 0, 0);
        record(paths, network, ServiceKey.parse("a/express"), 0, 0);
        List<MapGeometry.Segment> segments = MapGeometry.segments(network, paths, LOCATOR);
        assertEquals(1, segments.stream().filter(MapGeometry.Segment::recorded).count(), segments::toString);
    }
}
