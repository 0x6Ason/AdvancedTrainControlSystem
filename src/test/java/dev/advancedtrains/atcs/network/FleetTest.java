package dev.advancedtrains.atcs.network;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static dev.advancedtrains.atcs.TestNetworks.network;
import static dev.advancedtrains.atcs.TestNetworks.service;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FleetTest {

    @Test
    void parsesAndFormatsIntervalsLikeSpawnSigns() {
        assertEquals(Duration.ofSeconds(35), Fleet.parseEvery("00:35"));
        assertEquals(Duration.ofSeconds(90), Fleet.parseEvery("1:30"));
        assertEquals(Duration.ofSeconds(3661), Fleet.parseEvery("1:01:01"));
        assertEquals(Duration.ofSeconds(45), Fleet.parseEvery("45"));
        assertEquals(Duration.ofSeconds(45), Fleet.parseEvery("45s"));
        assertEquals("00:35", Fleet.formatEvery(Duration.ofSeconds(35)));
        assertEquals("1:01:01", Fleet.formatEvery(Duration.ofSeconds(3661)));
        assertThrows(IllegalArgumentException.class, () -> Fleet.parseEvery("soon"));
    }

    @Test
    void readsDirectionsFromYaw() {
        assertEquals(Fleet.Direction.SOUTH, Fleet.Direction.fromYaw(0));
        assertEquals(Fleet.Direction.WEST, Fleet.Direction.fromYaw(90));
        assertEquals(Fleet.Direction.NORTH, Fleet.Direction.fromYaw(180));
        assertEquals(Fleet.Direction.EAST, Fleet.Direction.fromYaw(-90));
        assertEquals(Fleet.Direction.EAST, Fleet.Direction.fromYaw(270));
        assertEquals(Fleet.Direction.NORTH, Fleet.Direction.parse(" North "));
        assertThrows(IllegalArgumentException.class, () -> Fleet.Direction.parse("up"));
    }

    @Test
    void validatesItsFields() {
        BlockPos spawn = new BlockPos("world", 1, 64, 2);
        assertThrows(IllegalArgumentException.class, () -> new Fleet(0, Duration.ofSeconds(30), spawn, Fleet.Direction.EAST, "4m"));
        assertThrows(IllegalArgumentException.class, () -> new Fleet(2, Duration.ZERO, spawn, Fleet.Direction.EAST, "4m"));
        assertThrows(IllegalArgumentException.class, () -> new Fleet(2, Duration.ofSeconds(30), spawn, Fleet.Direction.EAST, " "));
        Fleet fleet = new Fleet(3, Duration.ofSeconds(35), spawn, Fleet.Direction.EAST, " 4m ");
        assertEquals("4m", fleet.train());
        assertTrue(fleet.describe().contains("one every 00:35"));
    }

    @Test
    void roundTripsThroughNetworkYaml() {
        Fleet fleet = new Fleet(3, Duration.ofSeconds(35), new BlockPos("world", 10, 64, -5), Fleet.Direction.WEST, "4m");
        Network network = network(3, service("5/cw", "s1", "s2", "s3").withCircular(true).withFleet(fleet));
        NetworkCodec codec = new NetworkCodec();
        NetworkCodec.ReadResult result = codec.read(codec.write(network));
        assertTrue(result.clean(), () -> result.errors().toString());
        Service read = result.network().service(ServiceKey.parse("5/cw")).orElseThrow();
        assertEquals(fleet, read.fleet());
        assertEquals(network.service(ServiceKey.parse("5/cw")).orElseThrow(), read);

        NetworkCodec.ReadResult broken = codec.read("""
                stations: { s1: { name: One }, s2: { name: Two } }
                lines:
                  '5':
                    name: Line 5
                    services:
                      cw:
                        stops: [s1, s2]
                        fleet: { trains: 2, every: soon, spawn: "world,1,2,3", direction: east, train: 4m }
                """);
        assertEquals(List.of(), broken.network().services().stream().toList());
        assertTrue(broken.errors().get(0).contains("fleet"), broken.errors()::toString);
    }
}
