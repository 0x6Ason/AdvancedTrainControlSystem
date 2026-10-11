package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.PlatformRef;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers the stations each train visited from its creation to its removal. The record is used
 * to suggest services for networks that run on TrainCarts signs alone.
 */
public final class TripLog {
    static final int MAX_VISITS = 200;
    static final int MAX_TRIPS = 1000;

    /**
     * @param spawnSign the TrainCarts spawn sign that created the train, when it was seen
     * @param started   whether the train was seen from its creation
     * @param ended     whether the train was seen until its removal
     */
    public record Trip(@Nullable BlockPos spawnSign, List<PlatformRef> visits, boolean started, boolean ended) {
        public Trip {
            visits = List.copyOf(visits);
        }
    }

    private static final class Active {
        private final @Nullable BlockPos spawnSign;
        private final boolean started;
        private final List<PlatformRef> visits = new ArrayList<>();

        private Active(@Nullable BlockPos spawnSign, boolean started) {
            this.spawnSign = spawnSign;
            this.started = started;
        }
    }

    private final Map<String, Active> active = new HashMap<>();
    private final Deque<Trip> finished = new ArrayDeque<>();
    private boolean dirty;

    /** A new train appeared, possibly created by a spawn sign. */
    public void started(String trainId, @Nullable BlockPos spawnSign) {
        Active previous = active.put(trainId, new Active(spawnSign, true));
        if (previous != null) {
            // TrainCarts reuses names; the previous train with this name was not seen being removed.
            finish(previous, false);
        }
    }

    /** The spawn sign that created a running train, if it was seen. */
    public @Nullable BlockPos spawnSignOf(String trainId) {
        Active trip = active.get(trainId);
        return trip == null ? null : trip.spawnSign;
    }

    /** Records a station visit from a journey event. */
    public void record(JourneyEvent event) {
        PlatformRef platform = switch (event) {
            case JourneyEvent.Arrived arrived -> arrived.platform();
            case JourneyEvent.Passed passed -> passed.platform();
            case JourneyEvent.UnassignedArrived arrived -> arrived.platform();
            default -> null;
        };
        if (platform == null) {
            return;
        }
        Active trip = active.computeIfAbsent(event.trainId(), ignored -> new Active(null, false));
        List<PlatformRef> visits = trip.visits;
        if (!visits.isEmpty() && visits.get(visits.size() - 1).equals(platform)) {
            return;
        }
        visits.add(platform);
        if (visits.size() > MAX_VISITS) {
            visits.remove(0);
        }
        dirty = true;
    }

    /** Station ids the running train visited, oldest first, at most {@code max}. */
    public List<String> recentStations(String trainId, int max) {
        Active trip = active.get(trainId);
        if (trip == null) {
            return List.of();
        }
        List<PlatformRef> visits = trip.visits;
        return visits.subList(Math.max(0, visits.size() - max), visits.size()).stream()
                .map(PlatformRef::stationId)
                .toList();
    }

    /** The train was removed (for example by a destroy sign). */
    public void ended(String trainId) {
        Active trip = active.remove(trainId);
        if (trip != null) {
            finish(trip, true);
        }
    }

    private void finish(Active trip, boolean ended) {
        if (trip.visits.isEmpty()) {
            return;
        }
        finished.addLast(new Trip(trip.spawnSign, trip.visits, trip.started && trip.visits.size() < MAX_VISITS, ended));
        while (finished.size() > MAX_TRIPS) {
            finished.removeFirst();
        }
        dirty = true;
    }

    /** Whether finished trips changed since the last {@link #save}. */
    public boolean dirty() {
        return dirty;
    }

    /**
     * Saves finished and running trips, one line each:
     * {@code started,ended;spawn sign;station:platform ...}. Running trips are saved as unfinished.
     */
    public void save(ConfigurationSection root) {
        List<Trip> all = trips();
        List<String> lines = new ArrayList<>(all.size());
        for (Trip trip : all) {
            StringBuilder line = new StringBuilder()
                    .append(trip.started() ? 's' : '-').append(trip.ended() ? 'e' : '-').append(';')
                    .append(trip.spawnSign() == null ? "" : trip.spawnSign().format()).append(';');
            for (int index = 0; index < trip.visits().size(); index++) {
                if (index > 0) {
                    line.append(' ');
                }
                line.append(trip.visits().get(index));
            }
            lines.add(line.toString());
        }
        root.set("trips", lines);
        dirty = false;
    }

    public void load(ConfigurationSection root) {
        finished.clear();
        for (String line : root.getStringList("trips")) {
            String[] parts = line.split(";", 3);
            if (parts.length < 3 || parts[0].length() < 2) {
                continue;
            }
            try {
                BlockPos sign = parts[1].isBlank() ? null : BlockPos.parse(parts[1]);
                List<PlatformRef> visits = new ArrayList<>();
                for (String visit : parts[2].strip().split("\\s+")) {
                    int colon = visit.indexOf(':');
                    if (colon > 0) {
                        visits.add(new PlatformRef(visit.substring(0, colon), visit.substring(colon + 1)));
                    }
                }
                if (!visits.isEmpty()) {
                    finished.addLast(new Trip(sign, visits, parts[0].charAt(0) == 's', parts[0].charAt(1) == 'e'));
                }
            } catch (IllegalArgumentException ignored) {
                // Trips are disposable; a corrupt line is skipped.
            }
        }
        while (finished.size() > MAX_TRIPS) {
            finished.removeFirst();
        }
        dirty = false;
    }

    /** Finished trips followed by the trips of trains still running. */
    public List<Trip> trips() {
        List<Trip> trips = new ArrayList<>(finished);
        for (Active trip : active.values()) {
            if (!trip.visits.isEmpty()) {
                trips.add(new Trip(trip.spawnSign, trip.visits, trip.started, false));
            }
        }
        return trips;
    }

    public int finishedTrips() {
        return finished.size();
    }

    public void clear() {
        active.clear();
        finished.clear();
        dirty = true;
    }
}
