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

    @Test
    void hoursAndOffPeakSetTheTargetByTimeOfDay() {
        Fleet base = new Fleet(4, Duration.ofSeconds(35), new BlockPos("world", 10, 64, -5), Fleet.Direction.WEST, "4m");
        assertEquals(4, base.target(java.time.LocalTime.of(3, 0)), "no hours: all day");
        Fleet timed = base.withHours(Fleet.Hours.parse("06:00-23:00")).withOffPeak(new Fleet.OffPeak(2, Fleet.Hours.parse("20:00-06:00")));
        assertEquals(0, timed.target(java.time.LocalTime.of(3, 0)), "closed at night");
        assertEquals(4, timed.target(java.time.LocalTime.of(12, 0)));
        assertEquals(2, timed.target(java.time.LocalTime.of(21, 30)), "off-peak in the evening");
        assertEquals(4, timed.target(java.time.LocalTime.of(6, 0)), "opening minute counts");
        assertEquals(0, timed.target(java.time.LocalTime.of(23, 0)), "closing minute does not");
        assertTrue(Fleet.Hours.parse("22:00-06:00").contains(java.time.LocalTime.of(1, 0)), "a span over midnight");
        assertEquals("22:00-06:00", Fleet.Hours.parse("22:00 - 6:00").format());
        assertThrows(IllegalArgumentException.class, () -> Fleet.Hours.parse("all day"));
        assertThrows(IllegalArgumentException.class, () -> Fleet.Hours.parse("06:00-06:00"));
        assertTrue(timed.describe().contains("running 06:00-23:00"));
    }
}
