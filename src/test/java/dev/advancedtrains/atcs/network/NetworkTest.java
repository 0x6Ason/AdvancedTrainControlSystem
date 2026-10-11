package dev.advancedtrains.atcs.network;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dev.advancedtrains.atcs.TestNetworks.network;
import static dev.advancedtrains.atcs.TestNetworks.service;
import static dev.advancedtrains.atcs.TestNetworks.trigger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkTest {

    @Test
    void indexesTriggersAndCallingLines() {
        Network network = network(3, service("a/north", "s1", "~s2", "s3"), service("b/x", "s2", "s3"));
        assertEquals(new PlatformRef("s2", "2"), network.platformAt(trigger("s2", "2")).orElseThrow());
        assertEquals(List.of("a", "b"), network.linesCallingAt("s3").stream().map(Line::id).toList());
        assertEquals(List.of("b"), network.linesCallingAt("s2").stream().map(Line::id).toList(),
                "a pass stop is not a call");
        assertEquals(2, network.servicesListing("s2").size());
        assertTrue(network.issues().isEmpty(), network.issues()::toString);
    }

    @Test
    void reportsBrokenReferencesWithoutFailing() {
        BlockPos shared = new BlockPos("world", 1, 2, 3);
        Network network = Network.builder()
                .station(new Station("s1", "One", Map.of("1", new Platform("1", "", List.of(shared))), null))
                .station(new Station("s2", "Two", Map.of("1", new Platform("1", "", List.of(shared))), null))
                .line(Line.of("a", "A", 0).withService(Service.of(ServiceKey.parse("a/x"), "X",
                        List.of(Stop.of("s1"), Stop.parse("s2:9"), Stop.of("ghost"))).withContinuesAs(ServiceKey.parse("a/y"))))
                .board(new Board("b", "s1", List.of("7"), List.of(shared)))
                .build();
        List<String> issues = network.issues();
        assertTrue(issues.stream().anyMatch(issue -> issue.contains("bound to both")), issues::toString);
        assertTrue(issues.stream().anyMatch(issue -> issue.contains("unknown platform '9'")), issues::toString);
        assertTrue(issues.stream().anyMatch(issue -> issue.contains("unknown station 'ghost'")), issues::toString);
        assertTrue(issues.stream().anyMatch(issue -> issue.contains("continues as unknown")), issues::toString);
        assertTrue(issues.stream().anyMatch(issue -> issue.contains("filters unknown platform")), issues::toString);
        assertTrue(issues.stream().anyMatch(issue -> issue.contains("also a platform trigger")), issues::toString);
        assertEquals(new PlatformRef("s1", "1"), network.platformAt(shared).orElseThrow(), "first binding wins");
    }

    @Test
    void parsesAndFormatsValues() {
        assertEquals(new BlockPos("my,world", -1, 64, 7), BlockPos.parse("my,world,-1, 64 ,7"));
        assertEquals("world,1,2,3", new BlockPos("world", 1, 2, 3).format());
        assertThrows(IllegalArgumentException.class, () -> BlockPos.parse("world,1,2"));
        assertEquals("golden-islands-st", Ids.slugify("Golden Islands St."));
        assertEquals("metal", Ids.slugify("Metal++"));
        assertEquals("cafe", Ids.slugify("Café"));
        assertEquals("main", Ids.normalize("station", " MAIN "));
        assertThrows(IllegalArgumentException.class, () -> Ids.normalize("station", "has space"));
        assertEquals(new Stop("main", "2", true), Stop.parse("~main:2"));
        assertEquals("~main:2", Stop.parse(" ~ main:2").format());
        assertEquals(new ServiceKey("a", "north"), ServiceKey.parse("A/North"));
        assertThrows(IllegalArgumentException.class, () -> ServiceKey.parse("a/b/c"));
        assertEquals(0xFF5555, Colors.parse("red"));
        assertEquals(0x123ABC, Colors.parse("123abc"));
        assertEquals("#123ABC", Colors.format(0x123ABC));
    }

    @Test
    void servicesKnowTheirShape() {
        Service linear = service("a/x", "s1", "~s2", "s3", "~s4");
        assertEquals(2, linear.nextCallingIndex(0));
        assertEquals(-1, linear.nextCallingIndex(2));
        assertEquals("s3", linear.destination().orElseThrow().stationId(), "a trailing pass stop is not the destination");
        assertEquals(0, linear.nextCallingIndex(-1));
        Service circle = linear.withCircular(true);
        assertEquals(0, circle.nextCallingIndex(2));
        assertTrue(circle.destination().isEmpty());
        assertTrue(!linear.fingerprint().equals(circle.fingerprint()));
    }

    @Test
    void stationMarkerIsTheCentreOfItsTriggers() {
        Station station = new Station("s", "S", Map.of(
                "1", new Platform("1", "", List.of(new BlockPos("world", 0, 60, 0))),
                "2", new Platform("2", "", List.of(new BlockPos("world", 10, 64, 4)))), null);
        assertEquals(new BlockPos("world", 5, 62, 2), station.markerPosition().orElseThrow());
    }
}
