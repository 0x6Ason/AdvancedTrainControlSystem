package dev.advancedtrains.atcs.runtime;

import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Fleet;
import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.advancedtrains.atcs.TestNetworks.network;
import static dev.advancedtrains.atcs.TestNetworks.service;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FleetRuntimeTest {
    private static final ServiceKey CW = ServiceKey.parse("5/cw");
    private static final ServiceKey CCW = ServiceKey.parse("5/ccw");
    private static final Instant T0 = Instant.parse("2026-10-11T12:00:00Z");

    private final Fleet fleet = new Fleet(2, Duration.ofSeconds(35), new BlockPos("world", 0, 64, 0), Fleet.Direction.EAST, "4m");
    private final Network network = network(3,
            service("5/cw", "s1", "s2", "s3").withCircular(true).withFleet(fleet),
            service("5/ccw", "s3", "s2", "s1").withCircular(true),
            service("a/north", "s1", "s2"));

    @Test
    void spawnsOnlyForShortFleetsAndSpacesThemOut() {
        FleetManager manager = new FleetManager();
        assertEquals(List.of(CW), manager.due(network, Map.of(), T0).stream().map(Service::key).toList(),
                "only the service with a fleet is considered");
        manager.attempted(CW, T0);
        assertTrue(manager.due(network, Map.of(CW, 1), T0.plusSeconds(20)).isEmpty(), "too soon");
        assertEquals(1, manager.due(network, Map.of(CW, 1), T0.plusSeconds(35)).size());
        assertTrue(manager.due(network, Map.of(CW, 2), T0.plusSeconds(100)).isEmpty(), "at strength");
        manager.forget(CW);
        assertEquals(1, manager.due(network, Map.of(CW, 0), T0).size());
    }

    @Test
    void namesTrainsAfterTheirServiceAndSkipsTakenNames() {
        Line line = network.line("5").orElseThrow().withShortName("5");
        Service service = network.service(CW).orElseThrow();
        assertEquals("5-cw-1", TrainNamer.name(AtcsSettingsFormat.DEFAULT, line, service, 1));
        assertEquals("5-cw-3", TrainNamer.firstFree(AtcsSettingsFormat.DEFAULT, line, service, Set.of("5-cw-1", "5-cw-2")::contains));
        Line spaced = network.line("a").orElseThrow().withShortName("Line A+");
        assertEquals("Line-A-north-7", TrainNamer.name("<line>-<service>-<n>", spaced,
                network.service(ServiceKey.parse("a/north")).orElseThrow(), 7));
        assertEquals("train", TrainNamer.sanitize("!!!"));
    }

    private static final class AtcsSettingsFormat {
        private static final String DEFAULT = "<line>-<service>-<n>";
    }
}
