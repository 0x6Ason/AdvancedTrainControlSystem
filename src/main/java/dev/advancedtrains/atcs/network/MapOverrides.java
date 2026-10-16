package dev.advancedtrains.atcs.network;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Hand adjustments to the website's schematic diagram, kept under {@code map:} in network.yml:
 * a station pinned to a cell of the grid, hidden (a depot), or with its label on a chosen side,
 * and a line drawn in another colour than its badge when that one is too light on paper.
 *
 * @param stations   adjustments by station id
 * @param lineColors diagram colours by line id, when they differ from the badge colour
 */
public record MapOverrides(Map<String, StationOverride> stations, Map<String, Integer> lineColors) {
    public static final MapOverrides EMPTY = new MapOverrides(Map.of(), Map.of());
    public static final Set<String> LABEL_SIDES = Set.of("left", "right", "above", "below");

    /**
     * @param column the grid column the station is pinned to, or null
     * @param row    the grid row, or null
     * @param label  which side the name goes: left, right, above or below; null for automatic
     * @param hidden whether the station is left off the diagram
     */
    public record StationOverride(@Nullable Integer column, @Nullable Integer row, @Nullable String label, boolean hidden) {
        public static final StationOverride NONE = new StationOverride(null, null, null, false);

        public StationOverride {
            if ((column == null) != (row == null)) {
                throw new IllegalArgumentException("A pin needs both a column and a row");
            }
            if (label != null) {
                label = label.strip().toLowerCase(Locale.ROOT);
                if (!LABEL_SIDES.contains(label)) {
                    throw new IllegalArgumentException("Label side must be left, right, above or below");
                }
            }
        }

        public boolean isNone() {
            return column == null && label == null && !hidden;
        }

        public StationOverride withPin(@Nullable Integer newColumn, @Nullable Integer newRow) {
            return new StationOverride(newColumn, newRow, label, hidden);
        }

        public StationOverride withLabel(@Nullable String newLabel) {
            return new StationOverride(column, row, newLabel, hidden);
        }

        public StationOverride withHidden(boolean newHidden) {
            return new StationOverride(column, row, label, newHidden);
        }
    }

    public MapOverrides {
        Map<String, StationOverride> normalizedStations = new LinkedHashMap<>();
        stations.forEach((id, override) -> {
            if (!override.isNone()) {
                normalizedStations.put(Ids.normalize("station", id), override);
            }
        });
        stations = Collections.unmodifiableMap(normalizedStations);
        Map<String, Integer> normalizedColors = new LinkedHashMap<>();
        lineColors.forEach((id, color) -> normalizedColors.put(Ids.normalize("line", id), color & 0xFFFFFF));
        lineColors = Collections.unmodifiableMap(normalizedColors);
    }

    public boolean isEmpty() {
        return stations.isEmpty() && lineColors.isEmpty();
    }

    public StationOverride station(String stationId) {
        return stations.getOrDefault(stationId, StationOverride.NONE);
    }

    public MapOverrides withStation(String stationId, StationOverride override) {
        Map<String, StationOverride> updated = new LinkedHashMap<>(stations);
        updated.put(stationId, override);
        return new MapOverrides(updated, lineColors);
    }

    public MapOverrides withoutStation(String stationId) {
        Map<String, StationOverride> updated = new LinkedHashMap<>(stations);
        updated.remove(stationId);
        return new MapOverrides(updated, lineColors);
    }

    public MapOverrides withLineColor(String lineId, @Nullable Integer color) {
        Map<String, Integer> updated = new LinkedHashMap<>(lineColors);
        if (color == null) {
            updated.remove(lineId);
        } else {
            updated.put(lineId, color);
        }
        return new MapOverrides(stations, updated);
    }

    public MapOverrides withoutLine(String lineId) {
        return withLineColor(lineId, null);
    }
}
