package dev.advancedtrains.atcs.network;

import dev.advancedtrains.atcs.fare.FareTable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.advancedtrains.atcs.TestNetworks.network;
import static dev.advancedtrains.atcs.TestNetworks.service;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkLintTest {

    private static long count(List<NetworkLint.Finding> findings, NetworkLint.Level level, String contains) {
        return findings.stream().filter(finding -> finding.level() == level && finding.text().contains(contains)).count();
    }

    @Test
    void aHealthyNetworkHasOnlyNotesAboutUnlearnedServices() {
        Network network = network(3, service("a/north", "s1", "s2", "s3"), service("a/south", "s3", "s2", "s1"));
        List<NetworkLint.Finding> findings = NetworkLint.check(network, NetworkLint.Inputs.none());
        assertTrue(findings.stream().noneMatch(finding -> finding.level() == NetworkLint.Level.PROBLEM), findings::toString);
        assertTrue(findings.stream().noneMatch(finding -> finding.level() == NetworkLint.Level.WARNING), findings::toString);
        assertEquals(0, count(findings, NetworkLint.Level.NOTE, "No train has been seen"), "learnedSegments says 1");
    }

    @Test
    void findsWhatWillNotWork() {
        Network network = network(3, service("a/north", "s1", "s2", "s2", "s3"))
                .toBuilder()
                .station(Station.of("lonely", "Lonely Halt"))
                .line(Line.of("empty", "Empty Line", 0x000000))
                .board(new Board("b1", "s1", List.of(), List.of(new BlockPos("world", 1, 2, 3))))
                .transfer(Transfer.of("s3", "lonely"))
                .build();
        FareTable fares = FareTable.EMPTY.withPair("s1", "ghost", new FareTable.Pair(3, true)).withZone("s1", "a").withZoneFare("a", "a", 2.0);
        NetworkLint.Inputs inputs = new NetworkLint.Inputs(Map.of(), position -> false, service -> 0, fares, (a, b) -> !a.equals("lonely") && !b.equals("lonely"));
        List<NetworkLint.Finding> findings = NetworkLint.check(network, inputs);
        assertEquals(1, count(findings, NetworkLint.Level.WARNING, "Lonely Halt has no platform"));
        assertEquals(1, count(findings, NetworkLint.Level.NOTE, "No service calls at Lonely Halt"));
        assertEquals(1, count(findings, NetworkLint.Level.NOTE, "Lonely Halt has no position"));
        assertEquals(1, count(findings, NetworkLint.Level.WARNING, "Empty Line has no services"));
        assertEquals(1, count(findings, NetworkLint.Level.WARNING, "twice in a row"));
        assertEquals(1, count(findings, NetworkLint.Level.NOTE, "No train has been seen running a/north"));
        assertEquals(1, count(findings, NetworkLint.Level.WARNING, "estimated from positions, but Lonely Halt has none"));
        assertEquals(1, count(findings, NetworkLint.Level.PROBLEM, "Board b1: the sign at 1 2 3 is gone"));
        assertEquals(1, count(findings, NetworkLint.Level.WARNING, "unknown station 'ghost'"));
        assertTrue(count(findings, NetworkLint.Level.NOTE, "is in no fare zone") >= 3);
        assertEquals(1, count(findings, NetworkLint.Level.WARNING, "have no route"));
    }

    @Test
    void platformsWithoutTriggersAreNotedUnlessASignWasSeen() {
        Station station = new Station("s9", "Nine", Map.of("1", Platform.of("1")), null);
        Network network = network(1, service("a/x", "s1", "s9")).toBuilder().station(station).build();
        List<NetworkLint.Finding> bare = NetworkLint.check(network, NetworkLint.Inputs.none());
        assertEquals(1, count(bare, NetworkLint.Level.WARNING, "Nine has platforms but no trigger sign"));
        NetworkLint.Inputs seen = new NetworkLint.Inputs(Map.of("s9", Set.of(new BlockPos("world", 0, 0, 0))), null, s -> 1, null, null);
        assertEquals(0, count(NetworkLint.check(network, seen), NetworkLint.Level.WARNING, "Nine has platforms"));
    }
}
