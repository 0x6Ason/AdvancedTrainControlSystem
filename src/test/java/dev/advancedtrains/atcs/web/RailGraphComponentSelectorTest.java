package dev.advancedtrains.atcs.web;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RailGraphComponentSelectorTest {
    @Test
    void keepsSmallComponentWithinEuclideanAnchorRadius() {
        List<RailGraphComponentSelector.Node> nodes = List.of(
                node("near", 0, 64, 0),
                node("near-next", 1, 64, 0));

        RailGraphComponentSelector.Selection selection = select(
                RailGraphScope.NETWORK, 100,
                nodes,
                List.of(edge("near", "near-next")),
                List.of(new RailGraphComponentSelector.Anchor(0, 68, 0)));

        assertEquals(1, selection.sourceComponentCount());
        assertEquals(1, selection.visibleComponentCount());
        assertEquals(1, selection.anchoredComponentCount());
        assertEquals(1, selection.returnedAnchoredComponentCount());
        assertEquals(Set.of("near", "near-next"), selectedIds(nodes, selection));
    }

    @Test
    void removesSmallComponentOutsideEuclideanAnchorRadius() {
        List<RailGraphComponentSelector.Node> nodes = List.of(
                node("far", 0, 64, 0),
                node("far-next", 1, 64, 0));

        RailGraphComponentSelector.Selection selection = select(
                RailGraphScope.NETWORK, 100,
                nodes,
                List.of(edge("far", "far-next")),
                List.of(new RailGraphComponentSelector.Anchor(0, 69, 0)));

        assertEquals(1, selection.sourceComponentCount());
        assertEquals(0, selection.visibleComponentCount());
        assertEquals(0, selection.anchoredComponentCount());
        assertEquals(0, selection.returnedAnchoredComponentCount());
        assertTrue(selectedIds(nodes, selection).isEmpty());
    }

    @Test
    void keepsComponentAtTwoHundredNodeThreshold() {
        List<RailGraphComponentSelector.Node> nodes = new ArrayList<>();
        List<RailGraphComponentSelector.Edge> edges = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            String id = "large-" + index;
            nodes.add(node(id, index, 64, 0));
            if (index > 0) {
                edges.add(edge("large-" + (index - 1), id));
            }
        }

        RailGraphComponentSelector.Selection selection = select(
                RailGraphScope.NETWORK, 200, nodes, edges, List.of());

        assertEquals(1, selection.visibleComponentCount());
        assertEquals(1, selection.selectedComponentCount());
        assertEquals(200, selectedIds(nodes, selection).size());
    }

    @Test
    void allEvidenceKeepsEveryComponent() {
        List<RailGraphComponentSelector.Node> nodes = List.of(
                node("first", 0, 64, 0),
                node("second", 10, 64, 0));

        RailGraphComponentSelector.Selection selection = select(
                RailGraphScope.ALL_EVIDENCE, 10, nodes, List.of(), List.of());

        assertEquals(2, selection.sourceComponentCount());
        assertEquals(2, selection.visibleComponentCount());
        assertFalse(selectedIds(nodes, selection).isEmpty());
        assertEquals(2, selectedIds(nodes, selection).size());
    }

    @Test
    void capKeepsWholeComponentsAndPrioritizesAnchoredComponents() {
        List<RailGraphComponentSelector.Node> nodes = List.of(
                node("large-0", 20, 64, 0),
                node("large-1", 21, 64, 0),
                node("large-2", 22, 64, 0),
                node("anchor-0", 0, 64, 0),
                node("anchor-1", 1, 64, 0),
                node("single", 40, 64, 0));
        List<RailGraphComponentSelector.Edge> edges = List.of(
                edge("large-0", "large-1"),
                edge("large-1", "large-2"),
                edge("anchor-0", "anchor-1"));

        RailGraphComponentSelector.Selection selection = select(
                RailGraphScope.ALL_EVIDENCE,
                3,
                nodes,
                edges,
                List.of(new RailGraphComponentSelector.Anchor(0, 64, 0)));

        assertEquals(Set.of("anchor-0", "anchor-1", "single"), selectedIds(nodes, selection));
        assertEquals(2, selection.visibleComponentCount());
        assertEquals(3, selection.selectedComponentCount());
        assertEquals(3, selection.sourceComponentCount());
        assertEquals(1, selection.anchoredComponentCount());
        assertEquals(1, selection.returnedAnchoredComponentCount());
        assertTrue(selection.truncated());
    }

    @Test
    void equalPriorityComponentsUseStableNodeIdTieBreak() {
        List<RailGraphComponentSelector.Node> nodes = List.of(
                node("z-0", 0, 64, 0),
                node("z-1", 1, 64, 0),
                node("a-0", 10, 64, 0),
                node("a-1", 11, 64, 0));

        RailGraphComponentSelector.Selection selection = select(
                RailGraphScope.ALL_EVIDENCE,
                2,
                nodes,
                List.of(edge("z-0", "z-1"), edge("a-0", "a-1")),
                List.of());

        assertEquals(Set.of("a-0", "a-1"), selectedIds(nodes, selection));
        assertEquals(1, selection.visibleComponentCount());
        assertEquals(2, selection.selectedComponentCount());
        assertTrue(selection.truncated());
    }

    @Test
    void sameAnchorStatusPrioritizesLargerComponent() {
        List<RailGraphComponentSelector.Node> nodes = List.of(
                node("small-0", 0, 64, 0),
                node("small-1", 1, 64, 0),
                node("large-0", 10, 64, 0),
                node("large-1", 11, 64, 0),
                node("large-2", 12, 64, 0));

        RailGraphComponentSelector.Selection selection = select(
                RailGraphScope.ALL_EVIDENCE,
                3,
                nodes,
                List.of(
                        edge("small-0", "small-1"),
                        edge("large-0", "large-1"),
                        edge("large-1", "large-2")),
                List.of());

        assertEquals(Set.of("large-0", "large-1", "large-2"), selectedIds(nodes, selection));
        assertEquals(1, selection.visibleComponentCount());
        assertEquals(2, selection.selectedComponentCount());
    }

    @Test
    void oversizedAnchoredComponentReportsSourceButNotReturnedAnchor() {
        List<RailGraphComponentSelector.Node> nodes = List.of(
                node("anchor-0", 0, 64, 0),
                node("anchor-1", 1, 64, 0),
                node("fallback", 20, 64, 0));

        RailGraphComponentSelector.Selection selection = select(
                RailGraphScope.ALL_EVIDENCE,
                1,
                nodes,
                List.of(edge("anchor-0", "anchor-1")),
                List.of(new RailGraphComponentSelector.Anchor(0, 64, 0)));

        assertEquals(Set.of("fallback"), selectedIds(nodes, selection));
        assertEquals(2, selection.selectedComponentCount());
        assertEquals(1, selection.visibleComponentCount());
        assertEquals(1, selection.anchoredComponentCount());
        assertEquals(0, selection.returnedAnchoredComponentCount());
        assertTrue(selection.truncated());
    }

    private static RailGraphComponentSelector.Selection select(
            RailGraphScope scope,
            int maximumElements,
            List<RailGraphComponentSelector.Node> nodes,
            List<RailGraphComponentSelector.Edge> edges,
            List<RailGraphComponentSelector.Anchor> anchors) {
        return RailGraphComponentSelector.classify(nodes, edges, anchors)
                .select(scope, maximumElements);
    }

    private static Set<String> selectedIds(
            List<RailGraphComponentSelector.Node> nodes,
            RailGraphComponentSelector.Selection selection) {
        Set<String> selected = new HashSet<>();
        for (int index = 0; index < nodes.size(); index++) {
            if (selection.includesNode(index)) {
                selected.add(nodes.get(index).id());
            }
        }
        return selected;
    }

    private static RailGraphComponentSelector.Node node(String id, int x, int y, int z) {
        return new RailGraphComponentSelector.Node(id, x, y, z);
    }

    private static RailGraphComponentSelector.Edge edge(String firstNodeId, String secondNodeId) {
        return new RailGraphComponentSelector.Edge(firstNodeId, secondNodeId);
    }
}
