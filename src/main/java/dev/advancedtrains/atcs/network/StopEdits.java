package dev.advancedtrains.atcs.network;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Edits of a service's stop list that keep the rest of the list as it is. */
public final class StopEdits {
    private StopEdits() {
    }

    public enum Anchor { FIRST, LAST, BEFORE, AFTER }

    /**
     * Inserts a stop at the start or end, or before or after the first stop at {@code stationId}.
     *
     * @throws IllegalArgumentException when the anchor station is not in the list
     */
    public static List<Stop> insert(List<Stop> stops, Stop stop, Anchor anchor, @Nullable String stationId) {
        List<Stop> result = new ArrayList<>(stops);
        switch (anchor) {
            case FIRST -> result.add(0, stop);
            case LAST -> result.add(stop);
            case BEFORE, AFTER -> {
                int index = indexOf(stops, stationId);
                if (index < 0) {
                    throw new IllegalArgumentException("'" + stationId + "' is not a stop of this service");
                }
                result.add(anchor == Anchor.BEFORE ? index : index + 1, stop);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Inserts a stop between two stops that follow each other, in either order (so one command
     * serves both directions of a line). On a circular service the last and first stop count as
     * following each other.
     *
     * @throws IllegalArgumentException when the two stations are not consecutive stops
     */
    public static List<Stop> insertBetween(List<Stop> stops, Stop stop, String first, String second, boolean circular) {
        int at = adjacentIndex(stops, first, second, circular);
        if (at < 0) {
            throw new IllegalArgumentException("'" + first + "' and '" + second + "' are not consecutive stops of this service");
        }
        List<Stop> result = new ArrayList<>(stops);
        result.add(at, stop);
        return List.copyOf(result);
    }

    /** Whether the two stations are consecutive stops of the list, in either order. */
    public static boolean adjacent(List<Stop> stops, String first, String second, boolean circular) {
        return adjacentIndex(stops, first, second, circular) >= 0;
    }

    /** The index at which a stop between the two would go, or -1. */
    private static int adjacentIndex(List<Stop> stops, String first, String second, boolean circular) {
        for (int index = 0; index + 1 < stops.size(); index++) {
            String here = stops.get(index).stationId();
            String next = stops.get(index + 1).stationId();
            if ((here.equals(first) && next.equals(second)) || (here.equals(second) && next.equals(first))) {
                return index + 1;
            }
        }
        if (circular && stops.size() >= 2) {
            String last = stops.get(stops.size() - 1).stationId();
            String head = stops.get(0).stationId();
            if ((last.equals(first) && head.equals(second)) || (last.equals(second) && head.equals(first))) {
                return stops.size();
            }
        }
        return -1;
    }

    /** Removes every stop at the station. */
    public static List<Stop> remove(List<Stop> stops, String stationId) {
        List<Stop> result = new ArrayList<>();
        for (Stop stop : stops) {
            if (!stop.stationId().equals(stationId)) {
                result.add(stop);
            }
        }
        if (result.size() == stops.size()) {
            throw new IllegalArgumentException("'" + stationId + "' is not a stop of this service");
        }
        return List.copyOf(result);
    }

    private static int indexOf(List<Stop> stops, @Nullable String stationId) {
        for (int index = 0; index < stops.size(); index++) {
            if (stops.get(index).stationId().equals(stationId)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * For each stop of {@code after}, the index of the same station in {@code before} walking both
     * lists in order, or -1 for a station that was inserted. Learned data is carried across an
     * edit with this, so adding or dropping one stop keeps what was learned about the others.
     */
    public static int[] matchIndices(List<Stop> before, List<Stop> after) {
        int[] mapping = new int[after.size()];
        int from = 0;
        for (int index = 0; index < after.size(); index++) {
            mapping[index] = -1;
            for (int candidate = from; candidate < before.size(); candidate++) {
                if (before.get(candidate).stationId().equals(after.get(index).stationId())) {
                    mapping[index] = candidate;
                    from = candidate + 1;
                    break;
                }
            }
        }
        return mapping;
    }
}
