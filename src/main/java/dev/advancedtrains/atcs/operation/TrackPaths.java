package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.network.StopEdits;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Records the track each service's trains follow between consecutive stops, so maps can draw lines
 * along the real railway. The first complete run of a segment is kept until the service's stops
 * change or the paths are cleared.
 */
public final class TrackPaths {
    /** Simplification tolerance in blocks. */
    static final double TOLERANCE = 0.75;
    private static final int MAX_RAW_POINTS = 20_000;

    public record Point(double x, double y, double z) {
    }

    public record Path(String world, List<Point> points) {
        public Path {
            points = List.copyOf(points);
        }
    }

    private record Recording(ServiceKey service, int fromIndex, List<Point> points, String[] world) {
    }

    private final Map<ServiceKey, ServicePaths> services = new HashMap<>();
    private final Map<String, Recording> recordings = new HashMap<>();
    private boolean dirty;
    private int version;

    public void onEvent(JourneyEvent event, Network network) {
        switch (event) {
            case JourneyEvent.Departed departed when departed.nextIndex() >= 0 ->
                    start(event.trainId(), network, departed.service(), departed.stopIndex());
            case JourneyEvent.Arrived arrived -> {
                finish(event.trainId(), network, arrived.service(), arrived.fromIndex());
                recordings.remove(event.trainId());
            }
            case JourneyEvent.Passed passed when passed.stopIndex() >= 0 -> {
                finish(event.trainId(), network, passed.service(), passed.fromIndex());
                start(event.trainId(), network, passed.service(), passed.stopIndex());
            }
            case JourneyEvent.Passed ignored -> {
                // Running through a station the service does not list: keep recording the segment.
            }
            case JourneyEvent.Approaching ignored -> {
                // Still on the same segment.
            }
            default -> recordings.remove(event.trainId());
        }
    }

    /** Whether a position sample for this train would be used. */
    public boolean isRecording(String trainId) {
        return recordings.containsKey(trainId);
    }

    /** Adds a head position of a recording train. */
    public void sample(String trainId, String world, double x, double y, double z) {
        Recording recording = recordings.get(trainId);
        if (recording == null) {
            return;
        }
        if (recording.world()[0] == null) {
            recording.world()[0] = world;
        } else if (!recording.world()[0].equals(world) || recording.points().size() >= MAX_RAW_POINTS) {
            recordings.remove(trainId);
            return;
        }
        List<Point> points = recording.points();
        Point point = new Point(x, y, z);
        if (points.isEmpty() || !points.get(points.size() - 1).equals(point)) {
            points.add(point);
        }
    }

    public void forget(String trainId) {
        recordings.remove(trainId);
    }

    public Optional<Path> path(Service service, int fromIndex) {
        ServicePaths paths = services.get(service.key());
        if (paths == null || !paths.fingerprint.equals(service.fingerprint())
                || fromIndex < 0 || fromIndex >= paths.segments.length) {
            return Optional.empty();
        }
        return Optional.ofNullable(paths.segments[fromIndex]);
    }

    /** How far from the recorded path a head position may be to count as on it, in blocks. */
    static final double NEAR = 8;

    /**
     * How much of the recorded path from stop {@code fromIndex} a train at the given head position
     * has covered, 0 to 1 by path length; empty when the segment is not recorded, the world
     * differs, or the position is further than {@link #NEAR} blocks from the path.
     */
    public OptionalDouble progress(Service service, int fromIndex, String world, double x, double y, double z) {
        Path path = path(service, fromIndex).orElse(null);
        if (path == null || !path.world().equals(world) || path.points().size() < 2) {
            return OptionalDouble.empty();
        }
        List<Point> points = path.points();
        double total = 0;
        double best = NEAR * NEAR;
        double bestAlong = -1;
        double along = 0;
        for (int index = 0; index + 1 < points.size(); index++) {
            Point start = points.get(index);
            Point end = points.get(index + 1);
            double dx = end.x() - start.x();
            double dy = end.y() - start.y();
            double dz = end.z() - start.z();
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double t = length == 0 ? 0 : ((x - start.x()) * dx + (y - start.y()) * dy + (z - start.z()) * dz) / (length * length);
            t = Math.max(0, Math.min(1, t));
            double px = start.x() + t * dx - x;
            double py = start.y() + t * dy - y;
            double pz = start.z() + t * dz - z;
            double distance = px * px + py * py + pz * pz;
            if (distance < best) {
                best = distance;
                bestAlong = along + t * length;
            }
            along += length;
            total = along;
        }
        if (bestAlong < 0 || total <= 0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(bestAlong / total);
    }

    public int recordedSegments(Service service) {
        ServicePaths paths = services.get(service.key());
        if (paths == null || !paths.fingerprint.equals(service.fingerprint())) {
            return 0;
        }
        int count = 0;
        for (Path path : paths.segments) {
            if (path != null) {
                count++;
            }
        }
        return count;
    }

    public void clear(ServiceKey key) {
        if (services.remove(key) != null) {
            dirty = true;
            version++;
        }
    }

    /** Whether paths changed since the last {@link #save}. */
    /**
     * Keeps recorded paths across an edit of a service's stops: a segment stays when both its stops
     * are still there and still follow each other.
     */
    public void remap(Service before, Service after) {
        ServicePaths old = services.get(before.key());
        if (old == null || !old.fingerprint.equals(before.fingerprint()) || old.segments.length != before.size()) {
            return;
        }
        int[] mapping = StopEdits.matchIndices(before.stops(), after.stops());
        ServicePaths fresh = new ServicePaths(after.fingerprint(), after.size());
        for (int index = 0; index < mapping.length; index++) {
            int source = mapping[index];
            int nextSource = index + 1 < mapping.length ? mapping[index + 1]
                    : after.circular() && mapping.length > 0 ? mapping[0] : -1;
            if (source >= 0 && nextSource >= 0 && nextSource == before.nextIndex(source)) {
                fresh.segments[index] = old.segments[source];
            }
        }
        services.put(after.key(), fresh);
        dirty = true;
        version++;
    }

    public boolean dirty() {
        return dirty;
    }

    /** Increases whenever a path is added or cleared. */
    public int version() {
        return version;
    }

    private void start(String trainId, Network network, ServiceKey key, int fromIndex) {
        Service service = network.service(key).orElse(null);
        if (service == null || service.nextIndex(fromIndex) < 0 || path(service, fromIndex).isPresent()) {
            recordings.remove(trainId);
            return;
        }
        recordings.put(trainId, new Recording(key, fromIndex, new ArrayList<>(), new String[1]));
    }

    private void finish(String trainId, Network network, ServiceKey key, int fromIndex) {
        Recording recording = recordings.remove(trainId);
        if (recording == null || fromIndex < 0 || !recording.service().equals(key)
                || recording.fromIndex() != fromIndex || recording.points().size() < 2) {
            return;
        }
        Service service = network.service(key).orElse(null);
        if (service == null) {
            return;
        }
        ServicePaths paths = services.get(key);
        if (paths == null || !paths.fingerprint.equals(service.fingerprint())
                || paths.segments.length != service.size()) {
            paths = new ServicePaths(service.fingerprint(), service.size());
            services.put(key, paths);
        }
        if (paths.segments[fromIndex] == null) {
            paths.segments[fromIndex] = new Path(recording.world()[0], simplify(recording.points(), TOLERANCE));
            dirty = true;
            version++;
        }
    }

    /** Ramer-Douglas-Peucker simplification. */
    static List<Point> simplify(List<Point> points, double tolerance) {
        if (points.size() < 3) {
            return List.copyOf(points);
        }
        boolean[] keep = new boolean[points.size()];
        keep[0] = true;
        keep[points.size() - 1] = true;
        ArrayList<int[]> stack = new ArrayList<>();
        stack.add(new int[] {0, points.size() - 1});
        double toleranceSquared = tolerance * tolerance;
        while (!stack.isEmpty()) {
            int[] range = stack.remove(stack.size() - 1);
            int farthest = -1;
            double farthestDistance = toleranceSquared;
            for (int index = range[0] + 1; index < range[1]; index++) {
                double distance = segmentDistanceSquared(points.get(index), points.get(range[0]), points.get(range[1]));
                if (distance > farthestDistance) {
                    farthest = index;
                    farthestDistance = distance;
                }
            }
            if (farthest >= 0) {
                keep[farthest] = true;
                stack.add(new int[] {range[0], farthest});
                stack.add(new int[] {farthest, range[1]});
            }
        }
        List<Point> result = new ArrayList<>();
        for (int index = 0; index < points.size(); index++) {
            if (keep[index]) {
                result.add(points.get(index));
            }
        }
        return result;
    }

    private static double segmentDistanceSquared(Point point, Point start, Point end) {
        double dx = end.x() - start.x();
        double dy = end.y() - start.y();
        double dz = end.z() - start.z();
        double lengthSquared = dx * dx + dy * dy + dz * dz;
        double t = lengthSquared == 0 ? 0 : ((point.x() - start.x()) * dx + (point.y() - start.y()) * dy
                + (point.z() - start.z()) * dz) / lengthSquared;
        t = Math.max(0, Math.min(1, t));
        double px = start.x() + t * dx - point.x();
        double py = start.y() + t * dy - point.y();
        double pz = start.z() + t * dz - point.z();
        return px * px + py * py + pz * pz;
    }

    public void save(ConfigurationSection root) {
        for (Map.Entry<ServiceKey, ServicePaths> entry : services.entrySet()) {
            ConfigurationSection node = root.createSection(entry.getKey().toString());
            ServicePaths paths = entry.getValue();
            node.set("fingerprint", paths.fingerprint);
            node.set("stops", paths.segments.length);
            ConfigurationSection segments = node.createSection("segments");
            for (int index = 0; index < paths.segments.length; index++) {
                Path path = paths.segments[index];
                if (path == null) {
                    continue;
                }
                ConfigurationSection segment = segments.createSection(Integer.toString(index));
                segment.set("world", path.world());
                StringBuilder encoded = new StringBuilder();
                for (Point point : path.points()) {
                    if (!encoded.isEmpty()) {
                        encoded.append(' ');
                    }
                    encoded.append(String.format(Locale.ROOT, "%.1f,%.1f,%.1f", point.x(), point.y(), point.z()));
                }
                segment.set("points", encoded.toString());
            }
        }
        dirty = false;
    }

    public void load(ConfigurationSection root) {
        services.clear();
        for (String key : root.getKeys(false)) {
            ConfigurationSection node = root.getConfigurationSection(key);
            if (node == null) {
                continue;
            }
            try {
                ServicePaths paths = new ServicePaths(node.getString("fingerprint", ""), node.getInt("stops"));
                ConfigurationSection segments = node.getConfigurationSection("segments");
                if (segments != null) {
                    for (String indexKey : segments.getKeys(false)) {
                        int index = Integer.parseInt(indexKey);
                        ConfigurationSection segment = segments.getConfigurationSection(indexKey);
                        if (segment == null || index < 0 || index >= paths.segments.length) {
                            continue;
                        }
                        paths.segments[index] = new Path(segment.getString("world", "world"),
                                parsePoints(segment.getString("points", "")));
                    }
                }
                services.put(ServiceKey.parse(key), paths);
            } catch (IllegalArgumentException ignored) {
                // Recorded paths are disposable; a corrupt entry is recorded again.
            }
        }
        dirty = false;
        version++;
    }

    private static List<Point> parsePoints(@Nullable String encoded) {
        List<Point> points = new ArrayList<>();
        if (encoded == null || encoded.isBlank()) {
            return points;
        }
        for (String token : encoded.strip().split("\\s+")) {
            String[] parts = token.split(",");
            points.add(new Point(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2])));
        }
        return points;
    }

    private static final class ServicePaths {
        private final String fingerprint;
        private final Path[] segments;

        private ServicePaths(String fingerprint, int size) {
            this.fingerprint = fingerprint;
            this.segments = new Path[Math.max(0, size)];
        }
    }
}
