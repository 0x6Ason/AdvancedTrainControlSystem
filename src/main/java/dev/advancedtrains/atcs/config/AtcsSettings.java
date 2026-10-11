package dev.advancedtrains.atcs.config;

import dev.advancedtrains.atcs.importer.StationImporter;
import dev.advancedtrains.atcs.operation.RouteMatcher;
import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Typed view of {@code config.yml}. Invalid values never stop the plugin: they are replaced by the
 * default and reported in {@link #warnings()}.
 */
public record AtcsSettings(
        Passenger passenger,
        Boards boards,
        Timing timing,
        BlueMap blueMap,
        SignLink signLink,
        Web web,
        Importer importer,
        Assignment assignment,
        Trains trains,
        Spawning spawning,
        boolean debug,
        List<String> warnings) {

    public static final int CONFIG_VERSION = 2;

    public enum UnassignedTrains { NONE, TITLE }

    public record Passenger(
            boolean enabled,
            boolean bossBar,
            boolean arrivalTitle,
            int titleFadeIn,
            int titleStay,
            int titleFadeOut,
            boolean chatAnnouncements,
            double approachDistance,
            boolean showPassing,
            UnassignedTrains unassignedTrains,
            @Nullable Sound arrivalSound,
            @Nullable Sound departureSound,
            @Nullable Sound approachingSound) {
    }

    public record Boards(int refreshTicks, double defaultDwellSeconds) {
    }

    public record Timing(double smoothing, int saveIntervalMinutes) {
    }

    public record BlueMap(
            boolean enabled,
            String markerSetLabel,
            boolean showStations,
            boolean showLines,
            boolean showTrains,
            int trainRefreshTicks,
            boolean recordTrackPaths,
            int lineWidth) {
    }

    public record SignLink(boolean enabled, int departuresPerStation) {
    }

    /**
     * The railway website: a folder of static files that any web server can serve.
     *
     * @param folder where to write; empty for BlueMap's web root plus {@code /atcs}
     */
    public record Web(boolean enabled, String folder, String title, int refreshSeconds, boolean blueMapButton) {
    }

    public record Importer(StationImporter.Settings settings, int chunksPerTick) {
    }

    /**
     * @param inferFromRoute give a train with no service the one service whose stop order matches
     *                       the stations it has visited
     * @param minStops       visits needed before a service is inferred
     */
    public record Assignment(boolean inferFromRoute, int minStops) {
    }

    /**
     * @param rename           name trains after their service when ATCS assigns one
     * @param nameFormat       with placeholders {@code <line>}, {@code <service>} and {@code <n>}
     * @param keepChunksLoaded switch TrainCarts' keep-chunks-loaded on for every train
     */
    public record Trains(boolean rename, String nameFormat, boolean keepChunksLoaded) {
        public static final String DEFAULT_NAME_FORMAT = "<line>-<service>-<n>";
    }

    /**
     * @param keepSpawnChunksLoaded hold the chunks of spawn signs and fleet spawn points
     * @param checkSeconds          how often fleets are checked and trains are spawned
     */
    public record Spawning(boolean keepSpawnChunksLoaded, int checkSeconds) {
    }

    public static AtcsSettings read(ConfigurationSection config) {
        List<String> warnings = new ArrayList<>();
        Reader reader = new Reader(config, warnings);

        Sound.Source category = Sound.Source.NAMES.value(
                reader.string("passenger.sounds.category", "ambient").toLowerCase(Locale.ROOT));
        if (category == null) {
            warnings.add("passenger.sounds.category: unknown category, using ambient");
            category = Sound.Source.AMBIENT;
        }
        float volume = (float) reader.decimal("passenger.sounds.volume", 0.8, 0, 10);
        float pitch = (float) reader.decimal("passenger.sounds.pitch", 1.0, 0.5, 2);
        Passenger passenger = new Passenger(
                reader.bool("passenger.enabled", true),
                reader.bool("passenger.boss-bar", true),
                reader.bool("passenger.arrival-title", true),
                reader.integer("passenger.title-fade-in-ticks", 5, 0, 200),
                reader.integer("passenger.title-stay-ticks", 50, 0, 600),
                reader.integer("passenger.title-fade-out-ticks", 15, 0, 200),
                reader.bool("passenger.chat-announcements", true),
                reader.decimal("passenger.approach-distance", 60, 0, 1000),
                reader.bool("passenger.show-passing", true),
                reader.enumeration("passenger.unassigned-trains", UnassignedTrains.TITLE, UnassignedTrains.class),
                reader.sound("passenger.sounds.arrival", "minecraft:block.note_block.chime", category, volume, pitch),
                reader.sound("passenger.sounds.departure", "", category, volume, pitch),
                reader.sound("passenger.sounds.approaching", "", category, volume, pitch));

        Boards boards = new Boards(
                reader.integer("boards.refresh-ticks", 20, 5, 1200),
                reader.decimal("boards.default-dwell-seconds", 10, 0, 600));
        Timing timing = new Timing(
                reader.decimal("timing.smoothing", 0.3, 0.01, 1),
                reader.integer("timing.save-interval-minutes", 5, 1, 1440));
        BlueMap blueMap = new BlueMap(
                reader.bool("integrations.bluemap.enabled", true),
                reader.string("integrations.bluemap.marker-set-label", "Railway"),
                reader.bool("integrations.bluemap.show-stations", true),
                reader.bool("integrations.bluemap.show-lines", true),
                reader.bool("integrations.bluemap.show-trains", true),
                reader.integer("integrations.bluemap.train-refresh-ticks", 40, 10, 1200),
                reader.bool("integrations.bluemap.record-track-paths", true),
                reader.integer("integrations.bluemap.line-width", 5, 1, 20));
        SignLink signLink = new SignLink(
                reader.bool("integrations.signlink.enabled", true),
                reader.integer("integrations.signlink.departures-per-station", 3, 1, 10));

        Web web = new Web(
                reader.bool("integrations.web.enabled", true),
                reader.string("integrations.web.folder", ""),
                reader.string("integrations.web.title", "Railway"),
                reader.integer("integrations.web.refresh-seconds", 3, 1, 60),
                reader.bool("integrations.web.bluemap-button", true));

        StationImporter.Settings defaults = StationImporter.Settings.defaults();
        Importer importer = new Importer(new StationImporter.Settings(
                reader.strings("importer.arrival-prefixes", defaults.arrivalPrefixes()),
                reader.strings("importer.station-name-suffixes", defaults.stationNameSuffixes()),
                reader.strings("importer.id-strip-suffixes", defaults.idStripSuffixes()),
                reader.integer("importer.search-radius", defaults.searchRadius(), 1, 128),
                reader.integer("importer.vertical-tolerance", defaults.verticalTolerance(), 0, 64),
                reader.integer("importer.cluster-radius", defaults.clusterRadius(), 1, 1024)),
                reader.integer("importer.chunks-per-tick", 8, 1, 256));

        Assignment assignment = new Assignment(
                reader.bool("assignment.infer-from-route", true),
                reader.integer("assignment.min-stops", 2, 1, RouteMatcher.MAX_WINDOW));

        Trains trains = new Trains(
                reader.bool("trains.rename", true),
                reader.string("trains.name-format", Trains.DEFAULT_NAME_FORMAT),
                reader.bool("trains.keep-chunks-loaded", false));
        Spawning spawning = new Spawning(
                reader.bool("spawning.keep-spawn-chunks-loaded", true),
                reader.integer("spawning.check-seconds", 5, 1, 600));

        return new AtcsSettings(passenger, boards, timing, blueMap, signLink, web, importer, assignment, trains, spawning,
                reader.bool("debug", false), List.copyOf(warnings));
    }

    private record Reader(ConfigurationSection config, List<String> warnings) {

        boolean bool(String path, boolean fallback) {
            if (config.isSet(path) && !config.isBoolean(path)) {
                warnings.add(path + ": expected true or false, using " + fallback);
                return fallback;
            }
            return config.getBoolean(path, fallback);
        }

        int integer(String path, int fallback, int min, int max) {
            if (!config.isSet(path)) {
                return fallback;
            }
            if (!config.isInt(path)) {
                warnings.add(path + ": expected a whole number, using " + fallback);
                return fallback;
            }
            int value = config.getInt(path);
            if (value < min || value > max) {
                warnings.add(path + ": must be between " + min + " and " + max + ", using " + fallback);
                return fallback;
            }
            return value;
        }

        double decimal(String path, double fallback, double min, double max) {
            if (!config.isSet(path)) {
                return fallback;
            }
            if (!config.isDouble(path) && !config.isInt(path)) {
                warnings.add(path + ": expected a number, using " + fallback);
                return fallback;
            }
            double value = config.getDouble(path);
            if (value < min || value > max) {
                warnings.add(path + ": must be between " + min + " and " + max + ", using " + fallback);
                return fallback;
            }
            return value;
        }

        String string(String path, String fallback) {
            String value = config.getString(path);
            return value == null ? fallback : value;
        }

        List<String> strings(String path, List<String> fallback) {
            return config.isList(path) ? config.getStringList(path) : fallback;
        }

        <E extends Enum<E>> E enumeration(String path, E fallback, Class<E> type) {
            String value = config.getString(path);
            if (value == null) {
                return fallback;
            }
            try {
                return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                warnings.add(path + ": unknown value '" + value + "', using " + fallback);
                return fallback;
            }
        }

        @Nullable Sound sound(String path, String fallback, Sound.Source category, float volume, float pitch) {
            String value = string(path, fallback).strip();
            if (value.isEmpty()) {
                return null;
            }
            try {
                return Sound.sound(Key.key(value.toLowerCase(Locale.ROOT)), category, volume, pitch);
            } catch (InvalidKeyException exception) {
                warnings.add(path + ": '" + value + "' is not a valid sound key; sound disabled");
                return null;
            }
        }
    }
}
