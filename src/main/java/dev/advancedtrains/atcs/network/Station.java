package dev.advancedtrains.atcs.network;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A passenger station made of one or more platforms.
 *
 * @param id        identifier used in commands, signs, and service stop lists
 * @param name      passenger-facing name, for example {@code "Golden Islands St."}
 * @param platforms platforms in display order
 * @param location  optional explicit map position; otherwise derived from the platform triggers
 * @param code      optional short code for sign variables, for example {@code "gb"}; when absent
 *                  one is derived from the id (see {@link StationCodes})
 */
public record Station(String id, String name, Map<String, Platform> platforms, @Nullable BlockPos location,
                      @Nullable String code) {

    public Station {
        id = Ids.normalize("station", id);
        name = Objects.requireNonNull(name, "name").strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Station '" + id + "' needs a name");
        }
        platforms = Collections.unmodifiableMap(new LinkedHashMap<>(platforms));
        code = code == null || code.isBlank() ? null : StationCodes.normalize(code);
    }

    public Station(String id, String name, Map<String, Platform> platforms, @Nullable BlockPos location) {
        this(id, name, platforms, location, null);
    }

    public static Station of(String id, String name) {
        return new Station(id, name, Map.of(), null);
    }

    public Optional<Platform> platform(String platformId) {
        return Optional.ofNullable(platforms.get(platformId));
    }

    /** The position to show on maps: the explicit location, or the centre of all platform triggers. */
    public Optional<BlockPos> markerPosition() {
        if (location != null) {
            return Optional.of(location);
        }
        String world = null;
        long sumX = 0;
        long sumY = 0;
        long sumZ = 0;
        int count = 0;
        for (Platform platform : platforms.values()) {
            for (BlockPos trigger : platform.triggers()) {
                if (world == null) {
                    world = trigger.world();
                } else if (!world.equals(trigger.world())) {
                    continue;
                }
                sumX += trigger.x();
                sumY += trigger.y();
                sumZ += trigger.z();
                count++;
            }
        }
        if (count == 0) {
            return Optional.empty();
        }
        return Optional.of(new BlockPos(world,
                Math.round((float) sumX / count),
                Math.round((float) sumY / count),
                Math.round((float) sumZ / count)));
    }

    public Station withName(String newName) {
        return new Station(id, newName, platforms, location, code);
    }

    public Station withLocation(@Nullable BlockPos newLocation) {
        return new Station(id, name, platforms, newLocation, code);
    }

    /** The station with an explicit short code, or with a derived one again when {@code null}. */
    public Station withCode(@Nullable String newCode) {
        return new Station(id, name, platforms, location, newCode);
    }

    public Station withPlatform(Platform platform) {
        Map<String, Platform> updated = new LinkedHashMap<>(platforms);
        updated.put(platform.id(), platform);
        return new Station(id, name, updated, location, code);
    }

    public Station withoutPlatform(String platformId) {
        Map<String, Platform> updated = new LinkedHashMap<>(platforms);
        updated.remove(platformId);
        return new Station(id, name, updated, location, code);
    }
}
