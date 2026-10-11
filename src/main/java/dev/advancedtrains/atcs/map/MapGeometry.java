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
        return List.copyOf(segments);
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
