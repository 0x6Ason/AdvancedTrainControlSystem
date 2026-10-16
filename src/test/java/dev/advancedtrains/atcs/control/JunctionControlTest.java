package dev.advancedtrains.atcs.control;

import dev.advancedtrains.atcs.operation.RailGraph;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JunctionControlTest {
    private static final List<RailGraph.Passing> AHEAD = List.of(new RailGraph.Passing("j1", 40));

    private static Map<String, List<JunctionControl.Approach>> coming(JunctionControl.Approach... approaches) {
        return Map.of("j1", List.of(approaches));
    }

    @Test
    void theNearerTrainGoesFirstUnlessLateOrListedFirst() {
        JunctionControl.Approach near = new JunctionControl.Approach("x", "b", false, 30);
        assertEquals(Optional.of("junction: giving way to x (30 blocks away)"),
                JunctionControl.giveWay("mine", "a", false, AHEAD, coming(near), List.of()), "x is nearer the junction");
        JunctionControl.Approach far = new JunctionControl.Approach("x", "b", false, 90);
        assertEquals(Optional.empty(), JunctionControl.giveWay("mine", "a", false, AHEAD, coming(far), List.of()), "we are nearer");
        assertEquals(Optional.empty(), JunctionControl.giveWay("mine", "a", true, AHEAD, coming(near), List.of()), "a late train goes first");
        assertTrue(JunctionControl.giveWay("mine", "a", false, AHEAD, coming(new JunctionControl.Approach("x", "b", true, 90)), List.of()).isPresent(),
                "and yields to a late one");
        assertEquals(Optional.empty(), JunctionControl.giveWay("mine", "a", false, AHEAD, coming(near), List.of("a", "b")), "line a is listed first");
        assertTrue(JunctionControl.giveWay("mine", "a", false, AHEAD, coming(far), List.of("b", "a")).isPresent(), "line b is listed first");
        assertTrue(JunctionControl.giveWay("mine", "a", true, AHEAD, coming(new JunctionControl.Approach("x", "b", false, 10)), List.of("a")).isPresent(),
                "a train 10 blocks from the junction cannot be stopped, whatever the priority");
    }

    @Test
    void trainsPastOrFarFromTheJunctionDoNotCount() {
        assertEquals(Optional.empty(), JunctionControl.giveWay("mine", "a", false, AHEAD,
                coming(new JunctionControl.Approach("x", "b", false, -5)), List.of()), "already through");
        assertEquals(Optional.empty(), JunctionControl.giveWay("mine", "a", false, AHEAD,
                coming(new JunctionControl.Approach("x", "b", false, JunctionControl.RANGE_BLOCKS + 1)), List.of()), "too far away");
        assertEquals(Optional.empty(), JunctionControl.giveWay("mine", "a", false, AHEAD,
                coming(new JunctionControl.Approach("mine", "a", false, 5)), List.of()), "our own approach");
        assertEquals(Optional.empty(), JunctionControl.giveWay("mine", "a", false,
                List.of(new RailGraph.Passing("j1", JunctionControl.RANGE_BLOCKS + 50)),
                coming(new JunctionControl.Approach("x", "b", false, 30)), List.of()), "a junction far down our stretch is not yet our business");
        assertEquals(Optional.empty(), JunctionControl.giveWay("mine", "a", false, AHEAD, Map.of(), List.of()), "nobody coming");
    }
}
