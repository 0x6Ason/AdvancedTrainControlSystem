package dev.advancedtrains.atcs.control;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.ServiceKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static dev.advancedtrains.atcs.TestNetworks.network;
import static dev.advancedtrains.atcs.TestNetworks.service;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SwitchRulesTest {
    private final Network network = network(4,
            service("a/local", "s1", "s2", "s3", "s4"),
            service("a/express", "s1", "s4"),
            service("b/all", "s1", "s2"));

    @Test
    void picksByServiceLineOrNextStationInOrder() {
        SwitchRules rules = SwitchRules.parse(List.of("left a/express", "right s2", "continue b"));
        assertEquals(Optional.of(Heading.LEFT), rules.choose(ServiceKey.parse("a/express"), "s4", network));
        assertEquals(Optional.of(Heading.RIGHT), rules.choose(ServiceKey.parse("a/local"), "s2", network), "by next station");
        assertEquals(Optional.of(Heading.RIGHT), rules.choose(ServiceKey.parse("b/all"), "s2", network),
                "the first rule that fits wins, even over the line rule");
        assertEquals(Optional.empty(), rules.choose(ServiceKey.parse("a/local"), "s3", network), "no rule fits");
        assertEquals(Optional.empty(), rules.choose(null, null, network), "a train without a service");
    }

    @Test
    void starAndBareDirectionCatchEveryTrain() {
        SwitchRules rules = SwitchRules.parse(List.of("left a", "", "right"));
        assertEquals(Optional.of(Heading.LEFT), rules.choose(ServiceKey.parse("a/local"), "s2", network));
        assertEquals(Optional.of(Heading.RIGHT), rules.choose(ServiceKey.parse("b/all"), "s2", network));
        assertEquals(Optional.of(Heading.RIGHT), rules.choose(null, null, network));
        assertEquals(Optional.of(Heading.LEFT), SwitchRules.parse(List.of("left *")).choose(null, null, network));
    }

    @Test
    void readsCommasAndAliasesAndReportsUnknownNames() {
        SwitchRules rules = SwitchRules.parse(List.of("L a, b", "R nowhere"));
        assertEquals(List.of(new SwitchRules.Rule(Heading.LEFT, List.of("a", "b")),
                new SwitchRules.Rule(Heading.RIGHT, List.of("nowhere"))), rules.rules());
        assertEquals(List.of("nowhere"), rules.unknownSelectors(network));
        assertEquals("switch the track by service: left a b; right nowhere", rules.describe());
    }

    @Test
    void rejectsLinesWithoutADirection() {
        assertThrows(IllegalArgumentException.class, () -> SwitchRules.parse(List.of("a/express left")));
        assertThrows(IllegalArgumentException.class, () -> SwitchRules.parse(List.of("", "")));
        assertThrows(IllegalArgumentException.class, () -> SwitchRules.parse(List.of("left bad!id")));
    }
}
