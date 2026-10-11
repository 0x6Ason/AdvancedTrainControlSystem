package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Ids;
import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.network.ServiceMatch;
import dev.advancedtrains.atcs.network.Stop;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Suggests lines and services from the trips trains were seen making.
 *
 * <p>Each trip is cut where the train turned around (it visited one station twice in a row, or went
 * back to the station it just came from). The pieces with the same sequence of stations are one
 * service; pieces that are just part of a longer service (trains seen mid-trip) count towards it.
 * Services sharing track become one line. Nothing is applied automatically.
 */
public final class RouteSuggester {
    private static final int[] PALETTE = {0xE53935, 0x1E88E5, 0x43A047, 0xFB8C00, 0x8E24AA, 0x00ACC1, 0xFDD835, 0x6D4C41};
    private static final Pattern LINE_NAME = Pattern.compile("(?i)\\bline\\s+([a-z0-9]{1,3})\\b");

    public record Suggestion(List<Line> lines, Map<ServiceKey, Integer> observations, int tripsAnalysed, int alreadyDefined) {
        public int serviceCount() {
            return lines.stream().mapToInt(line -> line.services().size()).sum();
        }
    }

    /** One distinct station sequence and what was seen of it. */
    private static final class Route {
        private final List<String> stations;
        private final List<Map<String, Integer>> platforms = new ArrayList<>();
        private final Map<BlockPos, Integer> spawnSigns = new HashMap<>();
        private final Map<List<String>, Integer> successors = new HashMap<>();
        private int count;

        private Route(List<String> stations) {
            this.stations = stations;
            for (int index = 0; index < stations.size(); index++) {
                platforms.add(new HashMap<>());
            }
        }
    }

    private RouteSuggester() {
    }

    public static Suggestion suggest(List<TripLog.Trip> trips, Network network, int minObservations) {
        return suggest(trips, network, minObservations, platform -> List.of());
    }

    /**
     * @param hints texts of signs near a platform, such as announcements; a line whose platforms
     *              mostly mention "Line A" is suggested as line {@code a}
     */
    public static Suggestion suggest(List<TripLog.Trip> trips, Network network, int minObservations,
                                     Function<PlatformRef, Collection<String>> hints) {
        Map<List<String>, Route> routes = new LinkedHashMap<>();
        Map<BlockPos, Integer> spawnTotals = new HashMap<>();
        for (TripLog.Trip trip : trips) {
            List<List<PlatformRef>> pieces = cut(trip.visits());
            List<String> previous = null;
            for (int index = 0; index < pieces.size(); index++) {
                List<PlatformRef> piece = pieces.get(index);
                if (piece.size() < 2) {
                    previous = null;
                    continue;
                }
                List<String> stations = piece.stream().map(PlatformRef::stationId).toList();
                Route route = routes.computeIfAbsent(stations, Route::new);
                route.count++;
                for (int position = 0; position < piece.size(); position++) {
                    route.platforms.get(position).merge(piece.get(position).platformId(), 1, Integer::sum);
                }
                if (index == 0 && trip.started() && trip.spawnSign() != null) {
                    route.spawnSigns.merge(trip.spawnSign(), 1, Integer::sum);
                    spawnTotals.merge(trip.spawnSign(), 1, Integer::sum);
                }
                if (previous != null) {
                    routes.get(previous).successors.merge(stations, 1, Integer::sum);
                }
                previous = stations;
            }
        }
        foldPartialRoutes(routes, minObservations);

        Set<List<String>> defined = new HashSet<>();
        for (Service service : network.services()) {
            defined.add(service.stops().stream().map(Stop::stationId).toList());
        }
        List<Route> kept = new ArrayList<>();
        int alreadyDefined = 0;
        for (Route route : routes.values()) {
            if (route.count < minObservations) {
                continue;
            }
            if (defined.contains(route.stations)) {
                alreadyDefined++;
                continue;
            }
            kept.add(route);
        }
        kept.sort(Comparator.comparingInt((Route route) -> -route.count).thenComparing(route -> String.join(",", route.stations)));

        List<List<Route>> groups = groupIntoLines(kept);
        Set<String> usedLineIds = new HashSet<>(network.lines().keySet());
        List<Line> lines = new ArrayList<>();
        Map<ServiceKey, Integer> observations = new HashMap<>();
        int lineNumber = 0;
        for (List<Route> group : groups) {
            lineNumber++;
            String token = lineToken(group, hints);
            String lineId = token == null ? null : token.toLowerCase(Locale.ROOT);
            if (lineId == null || usedLineIds.contains(lineId)) {
                token = null;
                int number = lineNumber;
                do {
                    lineId = "line-" + number++;
                } while (usedLineIds.contains(lineId));
            }
            usedLineIds.add(lineId);
            Map<Route, String> names = new LinkedHashMap<>();
            group.forEach(route -> names.put(route, serviceName(route, group)));
            Map<Route, ServiceKey> keys = new LinkedHashMap<>();
            Set<String> usedServiceIds = new HashSet<>();
            List<Route> idOrder = new ArrayList<>(group);
            idOrder.sort(Comparator.comparing((Route route) -> !names.get(route).equals("Local")));
            for (Route route : idOrder) {
                String base = "to-" + route.stations.get(route.stations.size() - 1);
                String serviceId = fit(base, "");
                if (usedServiceIds.contains(serviceId) && !names.get(route).equals("Local")) {
                    serviceId = fit(base, "-" + names.get(route).toLowerCase(Locale.ROOT));
                }
                for (int suffix = 2; usedServiceIds.contains(serviceId); suffix++) {
                    serviceId = fit(base, "-" + suffix);
                }
                usedServiceIds.add(serviceId);
                keys.put(route, new ServiceKey(lineId, serviceId));
            }
            int color = PALETTE[(lineNumber - 1) % PALETTE.length];
            Line line = token == null
                    ? Line.of(lineId, "Line " + lineNumber, color)
                    : new Line(lineId, "Line " + token.toUpperCase(Locale.ROOT), token.toUpperCase(Locale.ROOT), color, Map.of(), null);
            for (Route route : group) {
                ServiceKey key = keys.get(route);
                observations.put(key, route.count);
                boolean circular = route.count >= 2 && route.successors.getOrDefault(route.stations, 0) * 2 >= route.count;
                line = line.withService(new Service(key, names.get(route), stops(route), circular,
                        circular ? null : continuation(route, keys), ServiceMatch.NONE, spawnSigns(route, spawnTotals)));
            }
            lines.add(line);
        }
        return new Suggestion(lines, observations, trips.size(), alreadyDefined);
    }

    private static String fit(String base, String suffix) {
        return base.substring(0, Math.min(base.length(), Ids.MAX_LENGTH - suffix.length())) + suffix;
    }

    /** The line name most mentioned by signs at the line's platforms ("Line A Upper Local" gives A). */
    private static @Nullable String lineToken(List<Route> group, Function<PlatformRef, Collection<String>> hints) {
        Map<String, Integer> votes = new HashMap<>();
        Set<PlatformRef> platforms = new HashSet<>();
        for (Route route : group) {
            for (int index = 0; index < route.stations.size(); index++) {
                for (String platform : route.platforms.get(index).keySet()) {
                    platforms.add(new PlatformRef(route.stations.get(index), platform));
                }
            }
        }
        int total = 0;
        for (PlatformRef platform : platforms) {
            for (String text : hints.apply(platform)) {
                Matcher matcher = LINE_NAME.matcher(text);
                while (matcher.find()) {
                    votes.merge(matcher.group(1).toUpperCase(Locale.ROOT), 1, Integer::sum);
                    total++;
                }
            }
        }
        // Only a clear majority names the line; mixed signs at shared platforms are common.
        for (Map.Entry<String, Integer> vote : votes.entrySet()) {
            if (vote.getValue() * 2 > total && Ids.isValid(vote.getKey().toLowerCase(Locale.ROOT))) {
                return vote.getKey();
            }
        }
        return null;
    }

    /** Cuts a trip where the train turned around. */
    static List<List<PlatformRef>> cut(List<PlatformRef> visits) {
        List<List<PlatformRef>> pieces = new ArrayList<>();
        List<PlatformRef> current = new ArrayList<>();
        for (PlatformRef visit : visits) {
            if (current.isEmpty()) {
                current.add(visit);
                continue;
            }
            PlatformRef last = current.get(current.size() - 1);
            if (visit.stationId().equals(last.stationId())) {
                // Arrived at one platform of the terminus and left from another.
                pieces.add(current);
                current = new ArrayList<>(List.of(visit));
            } else if (current.size() >= 2 && visit.stationId().equals(current.get(current.size() - 2).stationId())) {
                // Went back the way it came: the last station was a terminus.
                pieces.add(current);
                current = new ArrayList<>(List.of(last, visit));
            } else if (current.stream().anyMatch(earlier -> earlier.stationId().equals(visit.stationId()))) {
                // Back at a station of this piece: a circular line has completed a lap.
                pieces.add(current);
                current = new ArrayList<>(List.of(visit));
            } else {
                current.add(visit);
            }
        }
        if (!current.isEmpty()) {
            pieces.add(current);
        }
        return pieces;
    }

    /** Adds routes that are a contiguous part of a longer route (trains seen mid-trip) to that route. */
    private static void foldPartialRoutes(Map<List<String>, Route> routes, int minObservations) {
        List<Route> byLength = new ArrayList<>(routes.values());
        byLength.sort(Comparator.comparingInt((Route route) -> route.stations.size()));
        for (Route shorter : byLength) {
            if (routes.get(shorter.stations) != shorter) {
                continue;
            }
            Route container = container(routes, shorter, minObservations);
            if (container == null) {
                continue;
            }
            int offset = indexOf(container.stations, shorter.stations);
            container.count += shorter.count;
            for (int position = 0; position < shorter.stations.size(); position++) {
                Map<String, Integer> target = container.platforms.get(offset + position);
                shorter.platforms.get(position).forEach((platform, count) -> target.merge(platform, count, Integer::sum));
            }
            shorter.spawnSigns.forEach((sign, count) -> container.spawnSigns.merge(sign, count, Integer::sum));
            shorter.successors.forEach((next, count) -> container.successors.merge(next, count, Integer::sum));
            routes.remove(shorter.stations);
            for (Route route : routes.values()) {
                Integer moved = route.successors.remove(shorter.stations);
                if (moved != null) {
                    route.successors.merge(container.stations, moved, Integer::sum);
                }
            }
        }
    }

    /**
     * The most observed longer route containing {@code shorter} as a contiguous part. A route seen
     * fewer than {@code minObservations} times cannot absorb others: one odd long trip must not
     * swallow a frequent short one.
     */
    private static @Nullable Route container(Map<List<String>, Route> routes, Route shorter, int minObservations) {
        Route container = null;
        for (Route longer : routes.values()) {
            if (longer != shorter && longer.count >= minObservations
                    && longer.stations.size() > shorter.stations.size()
                    && indexOf(longer.stations, shorter.stations) >= 0
                    && (container == null || longer.count > container.count)) {
                container = longer;
            }
        }
        return container;
    }

    private static int indexOf(List<String> haystack, List<String> needle) {
        for (int start = 0; start + needle.size() <= haystack.size(); start++) {
            if (haystack.subList(start, start + needle.size()).equals(needle)) {
                return start;
            }
        }
        return -1;
    }

    /** Services that share a stretch of track, or whose stations are a subset of another's, form a line. */
    private static List<List<Route>> groupIntoLines(List<Route> routes) {
        int[] parent = new int[routes.size()];
        for (int index = 0; index < parent.length; index++) {
            parent[index] = index;
        }
        for (int i = 0; i < routes.size(); i++) {
            for (int j = i + 1; j < routes.size(); j++) {
                if (related(routes.get(i), routes.get(j))) {
                    parent[find(parent, i)] = find(parent, j);
                }
            }
        }
        Map<Integer, List<Route>> groups = new LinkedHashMap<>();
        for (int index = 0; index < routes.size(); index++) {
            groups.computeIfAbsent(find(parent, index), ignored -> new ArrayList<>()).add(routes.get(index));
        }
        return new ArrayList<>(groups.values());
    }

    private static boolean related(Route a, Route b) {
        Set<String> pairs = new HashSet<>();
        for (int index = 0; index + 1 < a.stations.size(); index++) {
            pairs.add(pair(a.stations.get(index), a.stations.get(index + 1)));
        }
        for (int index = 0; index + 1 < b.stations.size(); index++) {
            if (pairs.contains(pair(b.stations.get(index), b.stations.get(index + 1)))) {
                return true;
            }
        }
        return subset(a, b) || subset(b, a);
    }

    private static String pair(String first, String second) {
        return first.compareTo(second) < 0 ? first + "|" + second : second + "|" + first;
    }

    private static boolean subset(Route small, Route large) {
        return small.stations.size() >= 2 && small.stations.size() < large.stations.size()
                && new HashSet<>(large.stations).containsAll(small.stations);
    }

    private static int find(int[] parent, int index) {
        while (parent[index] != index) {
            parent[index] = parent[parent[index]];
            index = parent[index];
        }
        return index;
    }

    /** "Express" for a service that calls at only some stations of another one in the same direction. */
    private static String serviceName(Route route, List<Route> line) {
        for (Route other : line) {
            if (other != route && subset(route, other) && sameDirection(route, other)) {
                return "Express";
            }
        }
        return "Local";
    }

    private static boolean sameDirection(Route small, Route large) {
        int first = large.stations.indexOf(small.stations.get(0));
        int last = large.stations.indexOf(small.stations.get(small.stations.size() - 1));
        return first >= 0 && last >= 0 && first < last;
    }

    /** Stops with the platform trains used, when at least 80% of observations agree. */
    private static List<Stop> stops(Route route) {
        List<Stop> stops = new ArrayList<>();
        for (int index = 0; index < route.stations.size(); index++) {
            Map<String, Integer> seen = route.platforms.get(index);
            int total = seen.values().stream().mapToInt(Integer::intValue).sum();
            String platform = seen.entrySet().stream()
                    .filter(entry -> entry.getValue() * 5 >= total * 4)
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
            stops.add(new Stop(route.stations.get(index), platform, false));
        }
        return stops;
    }

    private static @Nullable ServiceKey continuation(Route route, Map<Route, ServiceKey> keys) {
        Route best = null;
        int bestCount = 0;
        for (Map.Entry<Route, ServiceKey> candidate : keys.entrySet()) {
            Route next = candidate.getKey();
            int count = route.successors.getOrDefault(next.stations, 0);
            if (count > bestCount && next.stations.get(0).equals(route.stations.get(route.stations.size() - 1))) {
                best = next;
                bestCount = count;
            }
        }
        return best == null ? null : keys.get(best);
    }

    /** Spawn signs whose trains started this service at least half of the time. */
    private static List<BlockPos> spawnSigns(Route route, Map<BlockPos, Integer> totals) {
        return route.spawnSigns.entrySet().stream()
                .filter(entry -> entry.getValue() * 2 >= totals.getOrDefault(entry.getKey(), 0))
                .map(Map.Entry::getKey)
                .sorted(Comparator.comparing(BlockPos::format))
                .toList();
    }
}
