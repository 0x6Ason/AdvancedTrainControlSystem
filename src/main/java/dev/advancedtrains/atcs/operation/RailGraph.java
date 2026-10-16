package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.Stop;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The railway as one graph, merged from every recorded path: nodes at junctions, line ends and
 * platforms, edges along the track between them with their length and the lines that use them.
 * Independent of services, so maps can draw the track itself, junctions have a position, and
 * distances along the track are known between any two platforms the trains have linked.
 *
 * <p>Built from the simplified paths, rasterised to whole blocks: two services on the same rails
 * fall on the same cells, so their paths merge; a junction is where a cell has more than two
 * neighbours.
 */
public final class RailGraph {
    /** Simplification tolerance for edge polylines, in blocks. */
    static final double TOLERANCE = 0.75;

    /** What a node is. */
    public enum Kind { PLATFORM, JUNCTION, END }

    /** A place where edges meet. */
    public record Node(String id, String world, int x, int y, int z, Kind kind, Set<String> stations) {
    }

    /** A stretch of track between two nodes, as the train runs it from {@code from} to {@code to}. */
    public record Edge(String id, Node from, Node to, String world, List<TrackPaths.Point> points, double length, Set<String> lines) {
    }

    /** A junction a path passes, {@code distance} blocks along it. */
    public record Passing(String nodeId, double distance) {
    }

    private final List<Node> nodes;
    private final List<Edge> edges;
    private final Map<String, Node> nodesById;
    private final Map<String, List<Edge>> edgesByNode;
    /** The cells that belong to a junction node, for finding junctions along a path. */
    private final Map<Cell, String> junctionCells;

    private RailGraph(List<Node> nodes, List<Edge> edges) {
        this(nodes, edges, Map.of());
    }

    private RailGraph(List<Node> nodes, List<Edge> edges, Map<Cell, String> junctionCells) {
        this.junctionCells = Map.copyOf(junctionCells);
        this.nodes = List.copyOf(nodes);
        this.edges = List.copyOf(edges);
        Map<String, Node> byId = new HashMap<>();
        nodes.forEach(node -> byId.put(node.id(), node));
        this.nodesById = Collections.unmodifiableMap(byId);
        Map<String, List<Edge>> adjacency = new HashMap<>();
        for (Edge edge : edges) {
            adjacency.computeIfAbsent(edge.from().id(), ignored -> new ArrayList<>()).add(edge);
            adjacency.computeIfAbsent(edge.to().id(), ignored -> new ArrayList<>()).add(edge);
        }
        this.edgesByNode = Collections.unmodifiableMap(adjacency);
    }

    public static RailGraph empty() {
        return new RailGraph(List.of(), List.of());
    }

    public List<Node> nodes() {
        return nodes;
    }

    public List<Edge> edges() {
        return edges;
    }

    /** Nodes where the track branches: three or more edges meet, whether or not it is also a platform. */
    public List<Node> junctions() {
        return nodes.stream().filter(node -> node.kind() == Kind.JUNCTION || edgesAt(node).size() >= 3).toList();
    }

    public Node node(String id) {
        return nodesById.get(id);
    }

    public List<Edge> edgesAt(Node node) {
        return edgesByNode.getOrDefault(node.id(), List.of());
    }

    /** The length of a recorded path in blocks. */
    public static double length(TrackPaths.Path path) {
        double length = 0;
        List<TrackPaths.Point> points = path.points();
        for (int index = 0; index + 1 < points.size(); index++) {
            TrackPaths.Point a = points.get(index);
            TrackPaths.Point b = points.get(index + 1);
            length += Math.sqrt(Math.pow(a.x() - b.x(), 2) + Math.pow(a.y() - b.y(), 2) + Math.pow(a.z() - b.z(), 2));
        }
        return length;
    }

    /** The junctions a recorded path passes, in order, each with its distance along the path. */
    public List<Passing> junctionsAlong(TrackPaths.Path path) {
        List<Passing> passings = new ArrayList<>();
        if (junctionCells.isEmpty() || path.points().size() < 2) {
            return passings;
        }
        Set<String> seen = new HashSet<>();
        List<TrackPaths.Point> points = path.points();
        double along = 0;
        for (int index = 0; index + 1 < points.size(); index++) {
            TrackPaths.Point a = points.get(index);
            TrackPaths.Point b = points.get(index + 1);
            double length = Math.sqrt(Math.pow(b.x() - a.x(), 2) + Math.pow(b.y() - a.y(), 2) + Math.pow(b.z() - a.z(), 2));
            int steps = Math.max(1, (int) Math.ceil(length * 2));
            for (int step = 0; step <= steps; step++) {
                double t = (double) step / steps;
                Cell cell = new Cell(path.world(), (int) Math.floor(a.x() + (b.x() - a.x()) * t),
                        (int) Math.floor(a.y() + (b.y() - a.y()) * t + 0.001), (int) Math.floor(a.z() + (b.z() - a.z()) * t));
                String node = junctionCells.get(cell);
                if (node != null && seen.add(node)) {
                    passings.add(new Passing(node, along + length * t));
                }
            }
            along += length;
        }
        return passings;
    }

    /** Total track length in blocks, each stretch counted once. */
    public double totalLength() {
        return edges.stream().mapToDouble(Edge::length).sum();
    }

    /**
     * The shortest track distance between two platform nodes' stations, in blocks, or empty when
     * no recorded track joins them.
     */
    public java.util.OptionalDouble distance(String fromStationId, String toStationId) {
        List<Node> starts = nodes.stream().filter(node -> node.stations().contains(fromStationId)).toList();
        Set<String> goals = new HashSet<>();
        nodes.stream().filter(node -> node.stations().contains(toStationId)).forEach(node -> goals.add(node.id()));
        if (starts.isEmpty() || goals.isEmpty()) {
            return java.util.OptionalDouble.empty();
        }
        Map<String, Double> best = new HashMap<>();
        java.util.PriorityQueue<Map.Entry<String, Double>> queue = new java.util.PriorityQueue<>(Map.Entry.comparingByValue());
        for (Node start : starts) {
            best.put(start.id(), 0.0);
            queue.add(Map.entry(start.id(), 0.0));
        }
        while (!queue.isEmpty()) {
            Map.Entry<String, Double> current = queue.poll();
            if (current.getValue() > best.getOrDefault(current.getKey(), Double.MAX_VALUE)) {
                continue;
            }
            if (goals.contains(current.getKey())) {
                return java.util.OptionalDouble.of(current.getValue());
            }
            for (Edge edge : edgesByNode.getOrDefault(current.getKey(), List.of())) {
                String other = edge.from().id().equals(current.getKey()) ? edge.to().id() : edge.from().id();
                double total = current.getValue() + edge.length();
                if (total < best.getOrDefault(other, Double.MAX_VALUE)) {
                    best.put(other, total);
                    queue.add(Map.entry(other, total));
                }
            }
        }
        return java.util.OptionalDouble.empty();
    }

    // ---------------------------------------------------------------- building

    private record Cell(String world, int x, int y, int z) {
    }

    /** How far from a platform trigger the track still belongs to that platform, in blocks. */
    static final int PLATFORM_REACH = 6;

    /** A cell, or the cluster of cells around one platform trigger, as the graph sees it. */
    private static final class Unit {
        private final String id;
        private final String world;
        private final Set<String> stations = new TreeSet<>();
        private final Set<String> lines = new TreeSet<>();
        private final Set<Unit> neighbours = new LinkedHashSet<>();
        private double sumX;
        private double sumY;
        private double sumZ;
        private int count;
        private final boolean platform;

        private Unit(String id, String world, boolean platform) {
            this.id = id;
            this.world = world;
            this.platform = platform;
        }

        private void cover(Cell cell) {
            sumX += cell.x();
            sumY += cell.y();
            sumZ += cell.z();
            count++;
        }

        private int x() {
            return (int) Math.round(sumX / Math.max(1, count));
        }

        private int y() {
            return (int) Math.round(sumY / Math.max(1, count));
        }

        private int z() {
            return (int) Math.round(sumZ / Math.max(1, count));
        }
    }

    /** Builds the graph from every recorded segment of every service in the network. */
    public static RailGraph build(Network network, TrackPaths paths) {
        // Cells within reach of a platform trigger belong to that platform, so every run that
        // starts or ends a block or two apart still meets at one node.
        Map<BlockPos, PlatformRef> triggers = network.triggers();
        TriggerIndex triggerIndex = new TriggerIndex(triggers);
        Map<Cell, Unit> unitOf = new HashMap<>();
        Map<String, Unit> units = new LinkedHashMap<>();
        for (Line line : network.lines().values()) {
            for (Service service : line.services().values()) {
                for (int index = 0; index < service.size(); index++) {
                    int next = service.nextIndex(index);
                    TrackPaths.Path path = next < 0 ? null : paths.path(service, index).orElse(null);
                    if (path == null || path.points().size() < 2) {
                        continue;
                    }
                    List<Cell> cells = rasterise(path);
                    if (cells.isEmpty()) {
                        continue;
                    }
                    Unit previous = null;
                    for (int cell = 0; cell < cells.size(); cell++) {
                        Unit unit = unitFor(cells.get(cell), triggerIndex, unitOf, units);
                        unit.lines.add(line.id());
                        if (previous != null && previous != unit) {
                            previous.neighbours.add(unit);
                            unit.neighbours.add(previous);
                        }
                        previous = unit;
                    }
                    // A run starts where the train has left the platform, a train length past the
                    // trigger, so the first cell is joined to the platform it left; the last cell is
                    // normally at the trigger already.
                    attach(unitOf.get(cells.get(0)), service.stop(index), cells.get(0), triggers, triggerIndex, unitOf, units, line.id());
                    attach(unitOf.get(cells.get(cells.size() - 1)), service.stop(next), cells.get(cells.size() - 1),
                            triggers, triggerIndex, unitOf, units, line.id());
                }
            }
        }
        if (units.isEmpty()) {
            return empty();
        }
        // Nodes: platforms, junctions (three or more neighbours) and ends.
        Map<Unit, Node> nodeAt = new LinkedHashMap<>();
        for (Unit unit : units.values()) {
            int degree = unit.neighbours.size();
            Kind kind = unit.platform || !unit.stations.isEmpty() ? Kind.PLATFORM
                    : degree >= 3 ? Kind.JUNCTION : degree == 1 ? Kind.END : null;
            if (kind == null) {
                continue;
            }
            String id = kind.name().toLowerCase(java.util.Locale.ROOT) + "_" + unit.x() + "_" + unit.y() + "_" + unit.z();
            nodeAt.put(unit, new Node(id, unit.world, unit.x(), unit.y(), unit.z(), kind,
                    Collections.unmodifiableSet(new TreeSet<>(unit.stations))));
        }
        // Edges: walk from every node along each neighbour until the next node.
        List<Edge> edges = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map.Entry<Unit, Node> entry : nodeAt.entrySet()) {
            Unit start = entry.getKey();
            for (Unit first : start.neighbours) {
                List<Unit> chain = new ArrayList<>();
                chain.add(start);
                Unit previous = start;
                Unit current = first;
                int guard = 0;
                while (!nodeAt.containsKey(current) && guard++ < 1_000_000) {
                    chain.add(current);
                    Unit step = null;
                    for (Unit candidate : current.neighbours) {
                        if (candidate != previous) {
                            step = candidate;
                            break;
                        }
                    }
                    if (step == null) {
                        break;
                    }
                    previous = current;
                    current = step;
                }
                if (!nodeAt.containsKey(current)) {
                    continue;
                }
                chain.add(current);
                Node from = entry.getValue();
                Node to = nodeAt.get(current);
                String forward = from.id() + ">" + to.id() + ">" + chain.size() + ">" + chain.get(1).id;
                String backward = to.id() + ">" + from.id() + ">" + chain.size() + ">" + chain.get(chain.size() - 2).id;
                if (seen.contains(backward) || !seen.add(forward)) {
                    continue;
                }
                Set<String> lines = new TreeSet<>();
                double length = 0;
                List<TrackPaths.Point> points = new ArrayList<>();
                for (int index = 0; index < chain.size(); index++) {
                    Unit unit = chain.get(index);
                    lines.addAll(unit.lines);
                    points.add(new TrackPaths.Point(unit.x() + 0.5, unit.y(), unit.z() + 0.5));
                    if (index > 0) {
                        Unit last = chain.get(index - 1);
                        length += Math.sqrt(Math.pow(unit.x() - last.x(), 2) + Math.pow(unit.y() - last.y(), 2)
                                + Math.pow(unit.z() - last.z(), 2));
                    }
                }
                edges.add(new Edge("edge_" + edges.size(), from, to, start.world,
                        TrackPaths.simplify(points, TOLERANCE), length, Collections.unmodifiableSet(lines)));
            }
        }
        List<Node> used = new ArrayList<>();
        Set<String> usedIds = new LinkedHashSet<>();
        for (Edge edge : edges) {
            if (usedIds.add(edge.from().id())) {
                used.add(edge.from());
            }
            if (usedIds.add(edge.to().id())) {
                used.add(edge.to());
            }
        }
        // Where the track branches (three or more ways, platform or not): the cells of those nodes.
        Map<Cell, String> junctionCells = new HashMap<>();
        for (Map.Entry<Cell, Unit> entry : unitOf.entrySet()) {
            Node node = nodeAt.get(entry.getValue());
            if (node != null && usedIds.contains(node.id()) && entry.getValue().neighbours.size() >= 3) {
                junctionCells.put(entry.getKey(), node.id());
            }
        }
        return new RailGraph(used, edges, junctionCells);
    }

    /** How far from a stop's trigger a run may start or end and still be joined to that platform, in blocks. */
    static final int PLATFORM_LINK_REACH = 48;

    /** Joins the unit at a run's end to the platform node of the stop, unless it is one already. */
    private static void attach(Unit end, Stop stop, Cell cell, Map<BlockPos, PlatformRef> triggers, TriggerIndex index,
                               Map<Cell, Unit> unitOf, Map<String, Unit> units, String lineId) {
        if (end.platform) {
            end.stations.add(stop.stationId());
            return;
        }
        BlockPos nearest = null;
        double best = (double) PLATFORM_LINK_REACH * PLATFORM_LINK_REACH + 1;
        for (Map.Entry<BlockPos, PlatformRef> trigger : triggers.entrySet()) {
            PlatformRef platform = trigger.getValue();
            BlockPos at = trigger.getKey();
            if (!platform.stationId().equals(stop.stationId()) || !at.world().equals(cell.world())) {
                continue;
            }
            double distance = at.distanceSquared(cell.x() + 0.5, cell.y(), cell.z() + 0.5);
            if (stop.platformId() != null && !stop.platformId().equals(platform.platformId())) {
                distance *= 4;
            }
            if (distance < best) {
                best = distance;
                nearest = at;
            }
        }
        if (nearest == null) {
            end.stations.add(stop.stationId());
            return;
        }
        Unit platform = unitFor(new Cell(nearest.world(), nearest.x(), nearest.y(), nearest.z()), index, unitOf, units);
        platform.lines.add(lineId);
        platform.stations.add(stop.stationId());
        if (platform != end) {
            platform.neighbours.add(end);
            end.neighbours.add(platform);
        }
    }

    /**
     * Platform triggers in buckets of 8 by 8 blocks per world, so that finding the trigger nearest
     * to a cell looks at a few triggers instead of every one: the graph of a big railway has
     * hundreds of thousands of cells and hundreds of triggers.
     */
    private static final class TriggerIndex {
        private static final int SHIFT = 3;

        private record Bucket(String world, int x, int z) {
        }

        private final Map<Bucket, List<Map.Entry<BlockPos, PlatformRef>>> buckets = new HashMap<>();

        TriggerIndex(Map<BlockPos, PlatformRef> triggers) {
            for (Map.Entry<BlockPos, PlatformRef> trigger : triggers.entrySet()) {
                BlockPos at = trigger.getKey();
                buckets.computeIfAbsent(new Bucket(at.world(), at.x() >> SHIFT, at.z() >> SHIFT), bucket -> new ArrayList<>())
                        .add(trigger);
            }
        }

        /** The trigger nearest to the cell within {@code reach} blocks and four levels, or null. */
        Map.@Nullable Entry<BlockPos, PlatformRef> nearest(Cell cell, int reach) {
            Map.Entry<BlockPos, PlatformRef> nearest = null;
            double best = (double) reach * reach + 1;
            int span = (reach >> SHIFT) + 1;
            int centreX = cell.x() >> SHIFT;
            int centreZ = cell.z() >> SHIFT;
            for (int x = centreX - span; x <= centreX + span; x++) {
                for (int z = centreZ - span; z <= centreZ + span; z++) {
                    List<Map.Entry<BlockPos, PlatformRef>> here = buckets.get(new Bucket(cell.world(), x, z));
                    if (here == null) {
                        continue;
                    }
                    for (Map.Entry<BlockPos, PlatformRef> trigger : here) {
                        BlockPos at = trigger.getKey();
                        if (Math.abs(at.y() - cell.y()) > 4) {
                            continue;
                        }
                        double distance = at.distanceSquared(cell.x() + 0.5, cell.y(), cell.z() + 0.5);
                        if (distance < best) {
                            best = distance;
                            nearest = trigger;
                        }
                    }
                }
            }
            return nearest;
        }
    }

    private static Unit unitFor(Cell cell, TriggerIndex index, Map<Cell, Unit> unitOf, Map<String, Unit> units) {
        Unit existing = unitOf.get(cell);
        if (existing != null) {
            return existing;
        }
        Map.Entry<BlockPos, PlatformRef> found = index.nearest(cell, PLATFORM_REACH);
        BlockPos nearest = found == null ? null : found.getKey();
        PlatformRef platform = found == null ? null : found.getValue();
        String id = nearest == null ? cell.world() + ":" + cell.x() + "," + cell.y() + "," + cell.z()
                : "platform:" + nearest.format();
        Unit unit = units.get(id);
        if (unit == null) {
            unit = new Unit(id, cell.world(), nearest != null);
            if (platform != null) {
                unit.stations.add(platform.stationId());
            }
            units.put(id, unit);
        }
        unit.cover(cell);
        unitOf.put(cell, unit);
        return unit;
    }

    /** The whole blocks a path runs through, in order, each once. */
    static List<Cell> rasterise(TrackPaths.Path path) {
        List<Cell> cells = new ArrayList<>();
        List<TrackPaths.Point> points = path.points();
        @Nullable Cell last = null;
        for (int index = 0; index + 1 < points.size(); index++) {
            TrackPaths.Point a = points.get(index);
            TrackPaths.Point b = points.get(index + 1);
            double length = Math.sqrt(Math.pow(b.x() - a.x(), 2) + Math.pow(b.y() - a.y(), 2) + Math.pow(b.z() - a.z(), 2));
            int steps = Math.max(1, (int) Math.ceil(length * 2));
            for (int step = 0; step <= steps; step++) {
                double t = (double) step / steps;
                Cell cell = new Cell(path.world(),
                        (int) Math.floor(a.x() + (b.x() - a.x()) * t),
                        (int) Math.floor(a.y() + (b.y() - a.y()) * t + 0.001),
                        (int) Math.floor(a.z() + (b.z() - a.z()) * t));
                if (!cell.equals(last)) {
                    // A diagonal step that skips a corner cell is joined through one, so chains stay connected.
                    if (last != null && (Math.abs(cell.x() - last.x()) + Math.abs(cell.z() - last.z()) > 1
                            || Math.abs(cell.y() - last.y()) > 1)) {
                        cells.add(new Cell(path.world(), cell.x(), last.y(), last.z()));
                    }
                    cells.add(cell);
                    last = cell;
                }
            }
        }
        // Remove immediate repeats that the corner joining may have produced.
        List<Cell> result = new ArrayList<>();
        for (Cell cell : cells) {
            if (result.isEmpty() || !result.get(result.size() - 1).equals(cell)) {
                result.add(cell);
            }
        }
        return result;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RailGraph graph && nodes.equals(graph.nodes) && edges.equals(graph.edges);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodes, edges);
    }
}
