package dev.advancedtrains.atcs.control;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.network.PlatformRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static dev.advancedtrains.atcs.TestNetworks.network;
import static dev.advancedtrains.atcs.TestNetworks.service;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    /** A terminus with two platforms: the first free one is taken, then the fallback rule. */
    @Test
    void freePlatformRulesFillATerminusInTurn() {
        SwitchRules rules = SwitchRules.parse(List.of("left free s4:1", "right free s4:2", "left s4"));
        assertEquals("left free s4:1", rules.rules().get(0).format());
        assertEquals(Optional.of(new PlatformRef("s4", "1")), rules.rules().get(0).freePlatform());
        java.util.Set<PlatformRef> taken = new java.util.HashSet<>();
        SwitchRules.Context local = new SwitchRules.Context(ServiceKey.parse("a/local"), "s4", false, platform -> !taken.contains(platform));
        assertEquals(Optional.of(Heading.LEFT), rules.pick(local, network).map(SwitchRules.Rule::heading));
        taken.add(new PlatformRef("s4", "1"));
        assertEquals(Optional.of(Heading.RIGHT), rules.pick(local, network).map(SwitchRules.Rule::heading), "platform 1 taken");
        taken.add(new PlatformRef("s4", "2"));
        SwitchRules.Rule fallback = rules.pick(local, network).orElseThrow();
        assertEquals(Heading.LEFT, fallback.heading(), "both taken: the plain rule");
        assertTrue(fallback.freePlatform().isEmpty());
        SwitchRules.Context elsewhere = new SwitchRules.Context(ServiceKey.parse("a/local"), "s2", false, platform -> true);
        assertEquals(Optional.empty(), rules.pick(elsewhere, network), "a free platform counts only for trains going to that station");
        assertTrue(rules.unknownSelectors(network).isEmpty());
        assertEquals(List.of("platform s4:9"), SwitchRules.parse(List.of("left free s4:9")).unknownSelectors(network));
        assertThrows(IllegalArgumentException.class, () -> SwitchRules.parse(List.of("left free s4")), "a platform is needed");
    }

    @Test
    void depotBoundTrainsTakeTheDepotRuleAndNoOther() {
        SwitchRules rules = SwitchRules.parse(List.of("left a", "right depot", "continue *"));
        SwitchRules.Context toDepot = new SwitchRules.Context(null, null, true, platform -> true);
        assertEquals(Optional.of(Heading.RIGHT), rules.pick(toDepot, network).map(SwitchRules.Rule::heading));
        assertEquals(Optional.of(Heading.LEFT), rules.choose(ServiceKey.parse("a/local"), "s2", network), "others as before");
        SwitchRules withoutDepotRule = SwitchRules.parse(List.of("left a", "right s2"));
        assertEquals(Optional.empty(), withoutDepotRule.pick(toDepot, network), "a line or station rule never catches a depot train");
        assertEquals(Optional.of(Heading.LEFT), SwitchRules.parse(List.of("left *")).pick(toDepot, network).map(SwitchRules.Rule::heading),
                "but * does");
        assertEquals("right depot", rules.rules().get(1).format());
        assertTrue(rules.unknownSelectors(network).isEmpty());
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
