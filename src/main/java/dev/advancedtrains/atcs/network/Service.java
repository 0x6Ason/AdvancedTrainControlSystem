package dev.advancedtrains.atcs.network;

import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A directional stopping pattern of a line, for example "Local towards New Village St.".
 *
 * @param key         line and service id
 * @param name        passenger-facing service name, for example {@code "Local"} or {@code "Express"}
 * @param stops       stations in running order
 * @param circular    whether the last stop is followed by the first one again
 * @param continuesAs the service a train becomes after leaving the last stop, typically the
 *                    opposite direction of a line whose trains reverse at the terminus
 * @param match       rules for assigning trains automatically
 * @param spawnSigns  TrainCarts spawn signs whose new trains run this service
 * @param fleet       trains ATCS itself keeps running on the service, or {@code null}
 */
public record Service(
        ServiceKey key,
        String name,
        List<Stop> stops,
        boolean circular,
        @Nullable ServiceKey continuesAs,
        ServiceMatch match,
        List<BlockPos> spawnSigns,
        @Nullable Fleet fleet) {

    public Service {
        Objects.requireNonNull(key, "key");
        name = Objects.requireNonNull(name, "name").strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Service '" + key + "' needs a name");
        }
        stops = List.copyOf(stops);
        Objects.requireNonNull(match, "match");
        spawnSigns = List.copyOf(spawnSigns);
    }

    public Service(ServiceKey key, String name, List<Stop> stops, boolean circular,
                   @Nullable ServiceKey continuesAs, ServiceMatch match, List<BlockPos> spawnSigns) {
        this(key, name, stops, circular, continuesAs, match, spawnSigns, null);
    }

    public Service(ServiceKey key, String name, List<Stop> stops, boolean circular,
                   @Nullable ServiceKey continuesAs, ServiceMatch match) {
        this(key, name, stops, circular, continuesAs, match, List.of(), null);
    }

    public static Service of(ServiceKey key, String name, List<Stop> stops) {
        return new Service(key, name, stops, false, null, ServiceMatch.NONE, List.of(), null);
    }

    public int size() {
        return stops.size();
    }

    public Stop stop(int index) {
        return stops.get(index);
    }

    public boolean isTerminus(int index) {
        return !circular && index == stops.size() - 1;
    }

    /** The index after {@code index}, or -1 at the end of a non-circular service. */
    public int nextIndex(int index) {
        if (stops.isEmpty()) {
            return -1;
        }
        if (index + 1 < stops.size()) {
            return index + 1;
        }
        return circular ? 0 : -1;
    }

    /** The next index after {@code index} at which the train stops (skipping pass stops), or -1. */
    public int nextCallingIndex(int index) {
        int candidate = index;
        for (int step = 0; step < stops.size(); step++) {
            candidate = nextIndex(candidate);
            if (candidate < 0) {
                return -1;
            }
            if (stops.get(candidate).stops()) {
                return candidate;
            }
        }
        return -1;
    }

    /**
     * The final destination shown to passengers: the last calling stop of a non-circular service.
     * Circular services have no destination; show the service name instead.
     */
    public Optional<Stop> destination() {
        if (circular) {
            return Optional.empty();
        }
        for (int index = stops.size() - 1; index >= 0; index--) {
            if (stops.get(index).stops()) {
                return Optional.of(stops.get(index));
            }
        }
        return Optional.empty();
    }

    /**
     * Where passengers are told the train is going when it is at (or just left) stop {@code index}:
     * the final destination, or on a circular service the next calling stop.
     */
    public Optional<Stop> destinationFrom(int index) {
        if (!circular) {
            return destination();
        }
        int next = nextCallingIndex(index);
        return next < 0 ? Optional.empty() : Optional.of(stops.get(next));
    }

    public boolean callsAt(String stationId) {
        return stops.stream().anyMatch(stop -> stop.stops() && stop.stationId().equals(stationId));
    }

    /** A stable hash of the stop list. Learned timings are discarded when it changes. */
    public String fingerprint() {
        StringBuilder text = new StringBuilder(circular ? "circular;" : "linear;");
        for (Stop stop : stops) {
            text.append(stop.format()).append(';');
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(text.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public Service withName(String newName) {
        return new Service(key, newName, stops, circular, continuesAs, match, spawnSigns, fleet);
    }

    public Service withStops(List<Stop> newStops) {
        return new Service(key, name, newStops, circular, continuesAs, match, spawnSigns, fleet);
    }

    public Service withCircular(boolean newCircular) {
        return new Service(key, name, stops, newCircular, continuesAs, match, spawnSigns, fleet);
    }

    public Service withContinuesAs(@Nullable ServiceKey newContinuesAs) {
        return new Service(key, name, stops, circular, newContinuesAs, match, spawnSigns, fleet);
    }

    public Service withMatch(ServiceMatch newMatch) {
        return new Service(key, name, stops, circular, continuesAs, newMatch, spawnSigns, fleet);
    }

    public Service withSpawnSigns(List<BlockPos> newSpawnSigns) {
        return new Service(key, name, stops, circular, continuesAs, match, newSpawnSigns, fleet);
    }

    public Service withFleet(@Nullable Fleet newFleet) {
        return new Service(key, name, stops, circular, continuesAs, match, spawnSigns, newFleet);
    }
}
