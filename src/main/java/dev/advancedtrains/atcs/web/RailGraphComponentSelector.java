package dev.advancedtrains.atcs.web;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Classifies complete rail graph components for display before response-size truncation.
 * This selection does not assign operational authority.
 */
final class RailGraphComponentSelector {
    static final int MINIMUM_NETWORK_COMPONENT_NODES = 200;
    static final long ANCHOR_RADIUS_SQUARED = 16L;

    private RailGraphComponentSelector() {
    }

    static Classification classify(
            List<Node> nodes,
            List<Edge> edges,
            List<Anchor> anchors) {
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(edges, "edges");
        Objects.requireNonNull(anchors, "anchors");

        Map<String, Integer> nodeIndexes = new HashMap<>();
        Map<Coordinate, Integer> indexByCoordinate = new HashMap<>();
        for (int index = 0; index < nodes.size(); index++) {
            Node node = Objects.requireNonNull(nodes.get(index), "node");
            if (nodeIndexes.put(node.id(), index) != null) {
                throw new IllegalArgumentException("Duplicate rail node id: " + node.id());
            }
            Coordinate coordinate = new Coordinate(node.x(), node.y(), node.z());
            if (indexByCoordinate.put(coordinate, index) != null) {
                throw new IllegalArgumentException("Duplicate rail node coordinate: " + coordinate);
            }
        }

        UnionFind unionFind = new UnionFind(nodes.size());
        for (Edge edge : edges) {
            Objects.requireNonNull(edge, "edge");
            Integer firstIndex = nodeIndexes.get(edge.firstNodeId());
            Integer secondIndex = nodeIndexes.get(edge.secondNodeId());
            if (firstIndex == null || secondIndex == null) {
                throw new IllegalArgumentException("Rail edge references a node outside the source graph");
            }
            unionFind.union(firstIndex, secondIndex);
        }

        Set<Integer> anchoredRoots = anchoredRoots(unionFind, indexByCoordinate, anchors);
        int[] rootByNodeIndex = new int[nodes.size()];
        Map<Integer, ComponentBuilder> builders = new LinkedHashMap<>();
        for (int index = 0; index < nodes.size(); index++) {
            int root = unionFind.find(index);
            rootByNodeIndex[index] = root;
            ComponentBuilder builder = builders.computeIfAbsent(
                    root, ignored -> new ComponentBuilder(anchoredRoots.contains(root)));
            builder.count++;
            String nodeId = nodes.get(index).id();
            if (builder.tieBreakId == null || nodeId.compareTo(builder.tieBreakId) < 0) {
                builder.tieBreakId = nodeId;
            }
        }
        for (ComponentBuilder builder : builders.values()) {
            builder.nodeIndexes = new int[builder.count];
        }
        for (int index = 0; index < rootByNodeIndex.length; index++) {
            ComponentBuilder builder = builders.get(rootByNodeIndex[index]);
            builder.nodeIndexes[builder.nextIndex++] = index;
        }

        List<Component> components = new ArrayList<>(builders.size());
        int anchoredComponentCount = 0;
        for (ComponentBuilder builder : builders.values()) {
            components.add(new Component(
                    builder.nodeIndexes, builder.anchored, builder.tieBreakId));
            if (builder.anchored) {
                anchoredComponentCount++;
            }
        }
        return new Classification(
                nodes.size(), List.copyOf(components), anchoredComponentCount);
    }

    private static Set<Integer> anchoredRoots(
            UnionFind unionFind,
            Map<Coordinate, Integer> indexByCoordinate,
            List<Anchor> anchors) {
        Set<Integer> roots = new HashSet<>();
        for (Anchor anchor : anchors) {
            for (int deltaX = -4; deltaX <= 4; deltaX++) {
                for (int deltaY = -4; deltaY <= 4; deltaY++) {
                    for (int deltaZ = -4; deltaZ <= 4; deltaZ++) {
                        long distanceSquared = (long) deltaX * deltaX
                                + (long) deltaY * deltaY
                                + (long) deltaZ * deltaZ;
                        if (distanceSquared > ANCHOR_RADIUS_SQUARED) {
                            continue;
                        }
                        Coordinate coordinate = offset(anchor, deltaX, deltaY, deltaZ);
                        if (coordinate == null) {
                            continue;
                        }
                        Integer nodeIndex = indexByCoordinate.get(coordinate);
                        if (nodeIndex != null) {
                            roots.add(unionFind.find(nodeIndex));
                        }
                    }
                }
            }
        }
        return roots;
    }

    private static Coordinate offset(Anchor anchor, int deltaX, int deltaY, int deltaZ) {
        long x = (long) anchor.x() + deltaX;
        long y = (long) anchor.y() + deltaY;
        long z = (long) anchor.z() + deltaZ;
        if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE
                || y < Integer.MIN_VALUE || y > Integer.MAX_VALUE
                || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) {
            return null;
        }
        return new Coordinate((int) x, (int) y, (int) z);
    }

    record Node(String id, int x, int y, int z) {
        Node {
            Objects.requireNonNull(id, "id");
        }
    }

    record Edge(String firstNodeId, String secondNodeId) {
        Edge {
            Objects.requireNonNull(firstNodeId, "firstNodeId");
            Objects.requireNonNull(secondNodeId, "secondNodeId");
        }
    }

    record Anchor(int x, int y, int z) {
    }

    record Classification(
            int sourceNodeCount,
            List<Component> components,
            int anchoredComponentCount) {
        Selection select(RailGraphScope scope, int maximumElements) {
            Objects.requireNonNull(scope, "scope");
            if (maximumElements <= 0) {
                throw new IllegalArgumentException("Rail graph maximum elements must be positive");
            }
            List<Component> selectedComponents = components.stream()
                    .filter(component -> scope == RailGraphScope.ALL_EVIDENCE
                            || component.anchored()
                            || component.size() >= MINIMUM_NETWORK_COMPONENT_NODES)
                    .sorted(RailGraphComponentSelector::compareDisplayPriority)
                    .toList();
            BitSet returnedNodeIndexes = new BitSet(sourceNodeCount);
            int returnedNodeCount = 0;
            int returnedComponentCount = 0;
            int returnedAnchoredComponentCount = 0;
            for (Component component : selectedComponents) {
                if (component.size() > maximumElements - returnedNodeCount) {
                    continue;
                }
                for (int nodeIndex : component.nodeIndexes()) {
                    returnedNodeIndexes.set(nodeIndex);
                }
                returnedNodeCount += component.size();
                returnedComponentCount++;
                if (component.anchored()) {
                    returnedAnchoredComponentCount++;
                }
            }
            return new Selection(
                    returnedNodeIndexes,
                    returnedComponentCount,
                    selectedComponents.size(),
                    components.size(),
                    anchoredComponentCount,
                    returnedAnchoredComponentCount,
                    returnedComponentCount < selectedComponents.size());
        }
    }

    /**
     * anchoredComponentCount covers the complete source graph, including components omitted by the cap.
     */
    record Selection(
            BitSet returnedNodeIndexes,
            int visibleComponentCount,
            int selectedComponentCount,
            int sourceComponentCount,
            int anchoredComponentCount,
            int returnedAnchoredComponentCount,
            boolean truncated) {
        boolean includesNode(int sourceNodeIndex) {
            return returnedNodeIndexes.get(sourceNodeIndex);
        }
    }

    record Component(int[] nodeIndexes, boolean anchored, String tieBreakId) {
        int size() {
            return nodeIndexes.length;
        }
    }

    private static int compareDisplayPriority(Component first, Component second) {
        int anchoredComparison = Boolean.compare(second.anchored(), first.anchored());
        if (anchoredComparison != 0) {
            return anchoredComparison;
        }
        int sizeComparison = Integer.compare(second.size(), first.size());
        if (sizeComparison != 0) {
            return sizeComparison;
        }
        return first.tieBreakId().compareTo(second.tieBreakId());
    }

    private record Coordinate(int x, int y, int z) {
    }

    private static final class ComponentBuilder {
        private final boolean anchored;
        private int count;
        private String tieBreakId;
        private int[] nodeIndexes;
        private int nextIndex;

        private ComponentBuilder(boolean anchored) {
            this.anchored = anchored;
        }
    }

    private static final class UnionFind {
        private final int[] parents;
        private final byte[] ranks;

        private UnionFind(int size) {
            parents = new int[size];
            ranks = new byte[size];
            for (int index = 0; index < size; index++) {
                parents[index] = index;
            }
        }

        private int find(int index) {
            int parent = parents[index];
            if (parent != index) {
                parents[index] = find(parent);
            }
            return parents[index];
        }

        private void union(int firstIndex, int secondIndex) {
            int firstRoot = find(firstIndex);
            int secondRoot = find(secondIndex);
            if (firstRoot == secondRoot) {
                return;
            }
            if (ranks[firstRoot] < ranks[secondRoot]) {
                parents[firstRoot] = secondRoot;
            } else if (ranks[firstRoot] > ranks[secondRoot]) {
                parents[secondRoot] = firstRoot;
            } else {
                parents[secondRoot] = firstRoot;
                ranks[firstRoot]++;
            }
        }
    }
}
