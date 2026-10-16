package dev.advancedtrains.atcs.map;

import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.Station;
import dev.advancedtrains.atcs.network.Stop;
import dev.advancedtrains.atcs.operation.TrackPaths;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Works out what a map should draw, independent of any particular map plugin. */
public final class MapGeometry {

    public record StationPoint(Station station, BlockPos position, List<Line> lines) {
    }

    /**
     * One drawn connection between two stations of a line.
     *
     * @param recorded whether the points follow a recorded track path rather than a straight line
     */
    public record Segment(String id, Line line, String world, List<TrackPaths.Point> points, boolean recorded) {
    }

    private MapGeometry() {
    }

    public static List<StationPoint> stations(Network network, StationLocator locator) {
        List<StationPoint> points = new ArrayList<>();
        for (Station station : network.stations().values()) {
            locator.station(station).ifPresent(position ->
                    points.add(new StationPoint(station, position, network.linesCallingAt(station.id()))));
        }
        return points;
    }

    /**
     * Every recorded segment of every service, so each direction runs along its own track and
     * meets the next segment at the platform. Identical paths (a local and an express on the same
     * stretch) are drawn once per line. A station pair without any recorded path is drawn as one
     * straight line.
     */
    public static List<Segment> segments(Network network, TrackPaths paths, StationLocator locator) {
        List<Segment> segments = new ArrayList<>();
        Map<String, Set<TrackPaths.Path>> drawn = new HashMap<>();
        Set<String> recordedPairs = new HashSet<>();
        Map<String, Segment> straight = new LinkedHashMap<>();
        for (Line line : network.lines().values()) {
            for (Service service : line.services().values()) {
                for (int index = 0; index < service.size(); index++) {
                    int next = service.nextIndex(index);
                    if (next < 0) {
                        continue;
                    }
                    Stop from = service.stop(index);
                    Stop to = service.stop(next);
                    if (from.stationId().equals(to.stationId())) {
                        continue;
                    }
                    String pair = pairId(line, from.stationId(), to.stationId());
                    Optional<TrackPaths.Path> recorded = paths.path(service, index);
                    if (recorded.isPresent()) {
                        recordedPairs.add(pair);
                        if (drawn.computeIfAbsent(line.id(), ignored -> new HashSet<>()).add(recorded.get())) {
                            segments.add(new Segment(line.id() + "_" + service.key().serviceId() + "_" + index,
                                    line, recorded.get().world(), recorded.get().points(), true));
                        }
                    } else if (!straight.containsKey(pair)) {
                        straight(network, locator, from, to).ifPresent(points ->
                                straight.put(pair, new Segment(pair, line, points.world(), points.points(), false)));
                    }
                }
            }
        }
        for (Map.Entry<String, Segment> entry : straight.entrySet()) {
            if (!recordedPairs.contains(entry.getKey())) {
                segments.add(entry.getValue());
            }
        }
        return List.copyOf(spread(segments));
    }

    /** How far apart lines on the same stretch of track are drawn, in blocks. */
    static final double SPREAD = 2.0;

    /**
     * Where several lines run the same stretch of track (same world, same two ends), draws them
     * side by side instead of on top of each other.
     */
    static List<Segment> spread(List<Segment> segments) {
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int index = 0; index < segments.size(); index++) {
            groups.computeIfAbsent(stretchKey(segments.get(index)), ignored -> new ArrayList<>()).add(index);
        }
        List<Segment> result = new ArrayList<>(segments);
        for (List<Integer> group : groups.values()) {
            Set<String> lines = new HashSet<>();
            for (int index : group) {
                lines.add(segments.get(index).line().id());
            }
            if (lines.size() < 2) {
                continue;
            }
            List<String> order = new ArrayList<>(lines);
            order.sort(String::compareTo);
            for (int index : group) {
                Segment segment = segments.get(index);
                int slot = order.indexOf(segment.line().id());
                double distance = (slot - (order.size() - 1) / 2.0) * SPREAD;
                result.set(index, new Segment(segment.id(), segment.line(), segment.world(), offset(segment.points(), distance), segment.recorded()));
            }
        }
        return result;
    }

    /** The same stretch whichever way it is run: world and its two ends, rounded to a few blocks. */
    private static String stretchKey(Segment segment) {
        TrackPaths.Point first = segment.points().get(0);
        TrackPaths.Point last = segment.points().get(segment.points().size() - 1);
        String a = Math.round(first.x() / 4) + "," + Math.round(first.z() / 4);
        String b = Math.round(last.x() / 4) + "," + Math.round(last.z() / 4);
        return segment.world() + "|" + (a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a);
    }

    /** Moves a polyline sideways by {@code distance} blocks (to its left for positive values), in the horizontal plane. */
    static List<TrackPaths.Point> offset(List<TrackPaths.Point> points, double distance) {
        if (distance == 0 || points.size() < 2) {
            return points;
        }
        List<TrackPaths.Point> result = new ArrayList<>(points.size());
        for (int index = 0; index < points.size(); index++) {
            TrackPaths.Point before = points.get(Math.max(0, index - 1));
            TrackPaths.Point after = points.get(Math.min(points.size() - 1, index + 1));
            double dx = after.x() - before.x();
            double dz = after.z() - before.z();
            double length = Math.sqrt(dx * dx + dz * dz);
            TrackPaths.Point point = points.get(index);
            if (length == 0) {
                result.add(point);
                continue;
            }
            result.add(new TrackPaths.Point(point.x() - dz / length * distance, point.y(), point.z() + dx / length * distance));
        }
        return result;
    }

    private static String pairId(Line line, String a, String b) {
        return line.id() + "_" + (a.compareTo(b) < 0 ? a + "_" + b : b + "_" + a);
    }

    private static Optional<TrackPaths.Path> straight(Network network, StationLocator locator, Stop from, Stop to) {
        Optional<BlockPos> start = network.station(from.stationId()).flatMap(station -> locator.stop(station, from));
        Optional<BlockPos> end = network.station(to.stationId()).flatMap(station -> locator.stop(station, to));
        if (start.isEmpty() || end.isEmpty() || !start.get().world().equals(end.get().world())) {
            return Optional.empty();
        }
        return Optional.of(new TrackPaths.Path(start.get().world(), List.of(center(start.get()), center(end.get()))));
    }

    private static TrackPaths.Point center(BlockPos position) {
        return new TrackPaths.Point(position.x() + 0.5, position.y() + 0.5, position.z() + 0.5);
    }
}
