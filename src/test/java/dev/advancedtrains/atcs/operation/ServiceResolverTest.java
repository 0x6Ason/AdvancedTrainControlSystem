package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.network.ServiceMatch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static dev.advancedtrains.atcs.TestNetworks.network;
import static dev.advancedtrains.atcs.TestNetworks.service;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceResolverTest {
    private final Network network = network(3,
            service("a/north", "s1", "s2", "s3")
                    .withMatch(new ServiceMatch(List.of("NewVil2Main"), List.of(), List.of())),
            service("a/south", "s3", "s2", "s1")
                    .withMatch(new ServiceMatch(List.of(), List.of("lineA-south"), List.of())),
            service("b/shuttle", "s1", "s2")
                    .withMatch(new ServiceMatch(List.of(), List.of(), List.of("shuttle*"))));

    private static TrainInfo train(String name, Set<String> tags, String destination, List<String> route) {
        return new TrainInfo(name, tags, destination, route);
    }

    @Test
    void assignmentTagWins() {
        TrainInfo info = train("train7", Set.of("ATCS:SERVICE=a/south"), "NewVil2Main", List.of());
        assertEquals(Optional.of(ServiceKey.parse("a/south")), ServiceResolver.resolve(info, network));
    }

    @Test
    void tagForUnknownServiceFallsBackToMatchRules() {
        TrainInfo info = train("train7", Set.of("atcs:service=x/gone"), "newvil2main", List.of());
        assertEquals(Optional.of(ServiceKey.parse("a/north")), ServiceResolver.resolve(info, network));
    }

    @Test
    void matchesDestinationRouteEntries() {
        TrainInfo info = train("train7", Set.of(), "", List.of("depot", "NewVil2Main"));
        assertEquals(Optional.of(ServiceKey.parse("a/north")), ServiceResolver.resolve(info, network));
    }

    @Test
    void matchesTagsIgnoringCase() {
        TrainInfo info = train("train7", Set.of("LINEA-SOUTH"), "", List.of());
        assertEquals(Optional.of(ServiceKey.parse("a/south")), ServiceResolver.resolve(info, network));
    }

    @Test
    void matchesTrainNameWildcards() {
        assertEquals(Optional.of(ServiceKey.parse("b/shuttle")),
                ServiceResolver.resolve(train("Shuttle12", Set.of(), "", List.of()), network));
        assertTrue(ServiceResolver.resolve(train("myshuttle", Set.of(), "", List.of()), network).isEmpty());
    }

    @Test
    void allConfiguredCriteriaMustMatch() {
        ServiceMatch both = new ServiceMatch(List.of("Main"), List.of("express"), List.of());
        assertTrue(ServiceResolver.matches(both, train("t", Set.of("express"), "Main", List.of())));
        assertTrue(!ServiceResolver.matches(both, train("t", Set.of("local"), "Main", List.of())));
    }

    @Test
    void readsMalformedTagsAsUnassigned() {
        assertTrue(ServiceResolver.taggedService(List.of("atcs:service=no-slash")).isEmpty());
        assertEquals(Optional.of(ServiceKey.parse("a/b")), ServiceResolver.taggedService(List.of("atcs:service=a/b")));
    }
}
