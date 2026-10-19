package dev.advancedtrains.atcs.network;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * A display ATCS keeps in the world: a picture on map items in item frames, or a hologram text.
 * Displays only ever touch frames and positions the operator selected, as boards only touch the
 * signs they bound.
 *
 * @param id         identifier
 * @param kind       what is shown
 * @param stationId  the station the display is about; {@code null} for a network map or status panel
 * @param platforms  platform ids a board lists; empty for all platforms
 * @param service    the service a roadmap draws
 * @param columns    frames across the mosaic
 * @param rows       frames down the mosaic
 * @param frames     the item frames, one per tile
 * @param maps       map ids of hand-held copies (the network map item), without a frame
 * @param hologram   where a hologram stands, for {@link Kind#HOLOGRAM}
 * @param options    how the picture is drawn: {@code left} or {@code right} and {@code strip},
 *                   {@code rows} or {@code column} for a roadmap, {@code new-york} or
 *                   {@code hong-kong} for a station sign
 * @param note       a smaller line a station sign shows under the name, or null
 */
public record Display(
        String id,
        Kind kind,
        @Nullable String stationId,
        List<String> platforms,
        @Nullable ServiceKey service,
        int columns,
        int rows,
        List<Frame> frames,
        List<Integer> maps,
        @Nullable Hologram hologram,
        List<String> options,
        @Nullable String note) {

    /** The words a display's options may hold. */
    public static final java.util.Set<String> OPTIONS = java.util.Set.of("left", "right", "strip", "rows", "column",
            "new-york", "hong-kong");

    public Display(String id, Kind kind, @Nullable String stationId, List<String> platforms, @Nullable ServiceKey service,
                   int columns, int rows, List<Frame> frames, List<Integer> maps, @Nullable Hologram hologram) {
        this(id, kind, stationId, platforms, service, columns, rows, frames, maps, hologram, List.of(), null);
    }

    /** What a display shows. */
    public enum Kind {
        /** The strip diagram of a service with "you are here". */
        ROADMAP(true, "Roadmap"),
        /** The station's logo. */
        LOGO(true, "Logo"),
        /** The next departures, as a picture. */
        BOARD(true, "Departure board"),
        /** The whole network drawn along its track. */
        MAP(false, "Network map"),
        /** Every line and whether it runs. */
        STATUS(false, "Service status"),
        /** The next departures as floating text. */
        HOLOGRAM(true, "Hologram board");

        private final boolean needsStation;
        private final String label;

        Kind(boolean needsStation, String label) {
            this.needsStation = needsStation;
            this.label = label;
        }

        public boolean needsStation() {
            return needsStation;
        }

        public String label() {
            return label;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Kind parse(String text) {
            try {
                return valueOf(text.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown display kind '" + text + "'");
            }
        }

        /** Whether the picture changes with the trains, so it is redrawn as boards are. */
        public boolean live() {
            return this == BOARD || this == HOLOGRAM;
        }
    }

    /**
     * One item frame of a mosaic.
     *
     * @param at     the block the frame hangs in
     * @param column tile column, 0 at the viewer's left
     * @param row    tile row, 0 at the top
     * @param map    the id of the map item in the frame
     */
    public record Frame(BlockPos at, int column, int row, int map) {
        public Frame {
            Objects.requireNonNull(at, "at");
            if (column < 0 || row < 0) {
                throw new IllegalArgumentException("Frame column and row must not be negative");
            }
        }
    }

    /**
     * A floating text.
     *
     * @param world  world name
     * @param entity the text display entity, once spawned
     * @param facing the way the text faces, as a yaw; null for a hologram that turns to each viewer
     */
    public record Hologram(String world, double x, double y, double z, @Nullable UUID entity, @Nullable Float facing) {
        public Hologram {
            Objects.requireNonNull(world, "world");
            if (world.isBlank()) {
                throw new IllegalArgumentException("A hologram needs a world");
            }
        }

        public Hologram(String world, double x, double y, double z, @Nullable UUID entity) {
            this(world, x, y, z, entity, null);
        }

        public Hologram withEntity(@Nullable UUID newEntity) {
            return new Hologram(world, x, y, z, newEntity, facing);
        }

        public Hologram withFacing(@Nullable Float newFacing) {
            return new Hologram(world, x, y, z, entity, newFacing);
        }

        public String formatPosition() {
            return world + "," + trim(x) + "," + trim(y) + "," + trim(z);
        }

        /** Parses {@code world,x,y,z} with decimals allowed; a world name may contain commas. */
        public static Hologram parse(String text, @Nullable UUID entity) {
            String[] parts = text.strip().split(",");
            if (parts.length < 4) {
                throw new IllegalArgumentException("Expected world,x,y,z but found '" + text + "'");
            }
            double[] numbers = new double[3];
            for (int index = 0; index < 3; index++) {
                String part = parts[parts.length - 3 + index].strip();
                try {
                    numbers[index] = Double.parseDouble(part);
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("'" + part + "' is not a number in '" + text + "'");
                }
            }
            String world = String.join(",", java.util.Arrays.copyOfRange(parts, 0, parts.length - 3)).strip();
            return new Hologram(world, numbers[0], numbers[1], numbers[2], entity);
        }

        private static String trim(double value) {
            String text = String.format(Locale.ROOT, "%.2f", value);
            if (text.contains(".")) {
                text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
            }
            return text;
        }
    }

    public Display {
        id = Ids.normalize("display", id);
        Objects.requireNonNull(kind, "kind");
        stationId = stationId == null || stationId.isBlank() ? null : Ids.normalize("station", stationId);
        platforms = platforms.stream().map(platform -> Ids.normalize("platform", platform)).toList();
        frames = List.copyOf(frames);
        maps = List.copyOf(maps);
        options = options.stream().map(option -> option.strip().toLowerCase(Locale.ROOT)).filter(option -> !option.isEmpty()).toList();
        for (String option : options) {
            if (!OPTIONS.contains(option)) {
                throw new IllegalArgumentException("Unknown display option '" + option + "'; use " + String.join(", ", OPTIONS.stream().sorted().toList()));
            }
        }
        note = note == null || note.isBlank() ? null : note.strip();
        if (kind.needsStation() && stationId == null) {
            throw new IllegalArgumentException("A " + kind.label().toLowerCase(Locale.ROOT) + " display needs a station");
        }
        if (kind == Kind.ROADMAP && service == null) {
            throw new IllegalArgumentException("A roadmap display needs a service");
        }
        if (kind == Kind.HOLOGRAM) {
            if (hologram == null) {
                throw new IllegalArgumentException("A hologram display needs a position");
            }
        } else if (frames.isEmpty() && maps.isEmpty()) {
            throw new IllegalArgumentException("A " + kind.label().toLowerCase(Locale.ROOT)
                    + " display needs item frames or a map");
        }
        if (columns < 0 || rows < 0) {
            throw new IllegalArgumentException("Columns and rows must not be negative");
        }
        for (Frame frame : frames) {
            if (frame.column() >= columns || frame.row() >= rows) {
                throw new IllegalArgumentException("Frame " + frame.at() + " is outside the " + columns + " x " + rows + " mosaic");
            }
        }
    }

    public boolean includesPlatform(String platformId) {
        return platforms.isEmpty() || platforms.contains(platformId);
    }

    /** Every map id the display draws on: its frames and hand-held copies. */
    public List<Integer> allMaps() {
        List<Integer> ids = new java.util.ArrayList<>(maps);
        frames.forEach(frame -> ids.add(frame.map()));
        return ids;
    }

    public Display withHologram(Hologram newHologram) {
        return new Display(id, kind, stationId, platforms, service, columns, rows, frames, maps, newHologram, options, note);
    }

    public Display withMaps(List<Integer> newMaps) {
        return new Display(id, kind, stationId, platforms, service, columns, rows, frames, newMaps, hologram, options, note);
    }

    public Display withOptions(List<String> newOptions, @Nullable String newNote) {
        return new Display(id, kind, stationId, platforms, service, columns, rows, frames, maps, hologram, newOptions, newNote);
    }

    public boolean option(String option) {
        return options.contains(option);
    }

    /** What the display shows, for lists: {@code Roadmap of 1/north at Main St. platform 1}. */
    public String describe(Network network) {
        StringBuilder text = new StringBuilder(kind.label());
        if (service != null) {
            text.append(" of ").append(service);
        }
        if (stationId != null) {
            text.append(" at ").append(network.stationName(stationId));
        }
        if (!platforms.isEmpty()) {
            text.append(" platform ").append(String.join(", ", platforms));
        }
        if (kind == Kind.HOLOGRAM && hologram != null) {
            text.append(" (").append(hologram.formatPosition()).append(")");
        } else if (!frames.isEmpty()) {
            text.append(" (").append(columns).append(" x ").append(rows).append(" frames at ")
                    .append(frames.get(0).at().coordinates()).append(")");
        } else if (!maps.isEmpty()) {
            text.append(" (map ").append(maps.get(0)).append(")");
        }
        return text.toString();
    }
}
