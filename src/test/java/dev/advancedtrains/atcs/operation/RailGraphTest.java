package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.NetworkCodec;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static dev.advancedtrains.atcs.TestNetworks.network;
import static dev.advancedtrains.atcs.TestNetworks.platform;
import static dev.advancedtrains.atcs.TestNetworks.service;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RailGraphTest {
    private static final Instant T0 = Instant.EPOCH;

    /** Records a straight run between two stops along the given corner points. */
    private static void record(TrackPaths paths, Network network, String key, int from, double[]... corners) {
        ServiceKey service = ServiceKey.parse(key);
        paths.onEvent(new JourneyEvent.Departed("t", T0, service, from, platform("s" + (from + 1)), from + 1, Duration.ofSeconds(5)), network);
        for (int index = 0; index + 1 < corners.length; index++) {
            double[] a = corners[index];
            double[] b = corners[index + 1];
            int steps = (int) Math.round(Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1])));
            for (int step = 0; step <= steps; step++) {
                double t = steps == 0 ? 0 : (double) step / steps;
                paths.sample("t", "world", a[0] + (b[0] - a[0]) * t + 0.5, 64, a[1] + (b[1] - a[1]) * t + 0.5);
            }
        }
        paths.onEvent(new JourneyEvent.Arrived("t", T0, service, from + 1, platform("s" + (from + 2)), false, from, Duration.ofSeconds(30)), network);
    }

    @Test
    void mergesSharedTrackAndFindsTheJunction() {
        // Triggers: s1 at (100, 4), s2 at (200, 4), s3 at (300, 4). Line a runs s1 east to s2; line b
        // shares the first 50 blocks, then loops north and round to s3.
        Network network = network(3, service("a/east", "s1", "s2"), service("b/north", "s1", "s3"));
        TrackPaths paths = new TrackPaths();
        record(paths, network, "a/east", 0, new double[] {100, 4}, new double[] {200, 4});
        record(paths, network, "b/north", 0, new double[] {100, 4}, new double[] {150, 4}, new double[] {150, -60},
                new double[] {300, -60}, new double[] {300, 4});
        RailGraph graph = RailGraph.build(network, paths);
        assertEquals(1, graph.junctions().size(), "one junction where b leaves the shared track: " + graph.nodes());
        RailGraph.Node junction = graph.junctions().get(0);
        assertEquals(150, junction.x());
        assertEquals(4, junction.z());
        assertEquals(3, graph.edges().size(), "shared stretch, a's rest, b's loop: " + graph.edges());
        RailGraph.Edge shared = graph.edges().stream().filter(edge -> edge.lines().size() == 2).findFirst().orElseThrow();
        assertEquals(50, shared.length(), 8);
        assertTrue(shared.from().stations().contains("s1") || shared.to().stations().contains("s1"));
        assertEquals(100 + 278, graph.totalLength(), 12, "the shared stretch is counted once");
        assertEquals(100, graph.distance("s1", "s2").orElseThrow(), 8);
        assertEquals(328, graph.distance("s1", "s3").orElseThrow(), 10, "through the junction");
        assertEquals(328, graph.distance("s3", "s1").orElseThrow(), 10, "edges are walked either way");
        assertEquals(50 + 278, graph.distance("s2", "s3").orElseThrow(), 10, "back through the junction");
        assertTrue(graph.distance("s1", "nowhere").isEmpty());
        long platforms = graph.nodes().stream().filter(node -> node.kind() == RailGraph.Kind.PLATFORM).count();
        assertEquals(3, platforms, "one node per platform: " + graph.nodes());
    }

    @Test
    void nothingRecordedIsAnEmptyGraph() {
        Network network = network(2, service("a/east", "s1", "s2"));
        RailGraph graph = RailGraph.build(network, new TrackPaths());
        assertTrue(graph.nodes().isEmpty());
        assertTrue(graph.edges().isEmpty());
        assertEquals(0, graph.totalLength());
    }

    @Test
    void buildsFromTheStagingRecordings() throws IOException {
        Path fixtures = Path.of("src/test/resources/fixtures/staging");
        Network network = new NetworkCodec().read(Files.readString(fixtures.resolve("network.yml"))).network();
        TrackPaths paths = new TrackPaths();
        paths.load(YamlConfiguration.loadConfiguration(fixtures.resolve("track-paths.yml").toFile()));
        RailGraph graph = RailGraph.build(network, paths);
        assertTrue(graph.edges().size() >= 10, "edges: " + graph.edges().size());
        assertTrue(graph.totalLength() > 1000, "total length: " + graph.totalLength());
        long platforms = graph.nodes().stream().filter(node -> node.kind() == RailGraph.Kind.PLATFORM).count();
        assertTrue(platforms >= 10, "platform nodes: " + platforms);
        assertTrue(platforms <= network.triggers().size() + 5, "runs meet at one node per platform: " + platforms);
        assertTrue(graph.junctions().size() >= 2, "the staging network has branches: " + graph.junctions().size());
        for (Service service : network.services()) {
            if (service.size() >= 2 && paths.path(service, 0).isPresent()) {
                assertTrue(graph.distance(service.stop(0).stationId(), service.stop(1).stationId()).isPresent(),
                        "the first segment of " + service.key() + " is joined up");
            }
        }
        List<RailGraph.Node> junctions = graph.junctions();
        System.out.println("staging rail graph: " + graph.nodes().size() + " nodes (" + platforms + " platforms, "
                + junctions.size() + " junctions), " + graph.edges().size() + " edges, "
                + Math.round(graph.totalLength()) + " blocks");
    }
}
