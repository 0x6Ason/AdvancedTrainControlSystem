package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Follows every train through the stops of its service and turns raw platform observations into
 * {@link JourneyEvent}s.
 *
 * <p>Rules:
 * <ul>
 *   <li>A station visit starts with the first trigger entered at that station and ends when the
 *       train has left every trigger of it, so long trains and repeated events are harmless.</li>
 *   <li>Stops are located by searching forward from the last stop reached, which tolerates stations
 *       without triggers and trains that were unloaded for part of the trip.</li>
 *   <li>The service resolved from the train's tags and match rules is applied only when that
 *       resolution changes, so a service switch made here (continuing as another service at a
 *       terminus) is not undone by the next observation.</li>
 * </ul>
 *
 * <p>Not thread-safe: call only from the server thread.
 */
public final class JourneyTracker {
    /** Entering the same station again this soon after leaving it resumes the visit silently. */
    static final Duration REENTRY_WINDOW = Duration.ofSeconds(15);

    private final Map<String, Journey> journeys = new HashMap<>();

    public List<JourneyEvent> observe(StopObservation observation, @Nullable ServiceKey resolved, Network network) {
        Journey journey = journeys.get(observation.trainId());
        if (journey == null) {
            if (!observation.entering()) {
                // A leave signal for a train we do not follow, for example one that was just removed.
                return List.of();
            }
            journey = new Journey(observation.trainId());
            journeys.put(journey.trainId, journey);
        }
        journey.lastSeen = observation.at();
        List<JourneyEvent> events = new ArrayList<>();
        if (!Objects.equals(resolved, journey.lastResolved)) {
            journey.lastResolved = resolved;
            if (!Objects.equals(resolved, journey.service)) {
                changeService(journey, service(network, resolved), observation.at(), events);
            }
        }
        Service service = service(network, journey.service);
        if (journey.service != null && service == null) {
            // The service was removed from the network while the train was running it.
            changeService(journey, null, observation.at(), events);
        }
        if (observation.entering()) {
            enter(journey, service, observation, network, events);
        } else {
            leave(journey, service, observation, network, events);
        }
        return events;
    }

    /** Assigns a service explicitly, for example from a command or an ATCS service sign. */
    public List<JourneyEvent> assign(String trainId, @Nullable ServiceKey key, Network network, Instant at) {
        Journey journey = journeys.computeIfAbsent(trainId, Journey::new);
        journey.lastSeen = at;
        journey.lastResolved = key;
        List<JourneyEvent> events = new ArrayList<>();
        if (!Objects.equals(key, journey.service)) {
            changeService(journey, service(network, key), at, events);
        }
        return events;
    }

    /**
     * Marks the train as approaching its next calling stop. Returns the event only the first time
     * for each stop, and only while the train is between stops.
     */
    public Optional<JourneyEvent> markApproaching(String trainId, Network network, Instant at) {
        Journey journey = journeys.get(trainId);
        if (journey == null || (journey.phase != JourneyPhase.IN_TRANSIT && journey.phase != JourneyPhase.IDLE)) {
            return Optional.empty();
        }
        Service service = service(network, journey.service);
        if (service == null) {
            return Optional.empty();
        }
        int next = service.nextCallingIndex(journey.phase == JourneyPhase.IDLE ? -1 : journey.lastIndex);
        if (next < 0 || journey.approachAnnouncedFor == next) {
            return Optional.empty();
        }
        journey.approachAnnouncedFor = next;
        return Optional.of(new JourneyEvent.Approaching(trainId, at, service.key(), next));
    }

    /**
     * Re-finds every train's position after the network changed: stop lists may have been edited,
     * services removed or stations deleted. Returns the service changes this causes.
     */
    public List<JourneyEvent> relocate(Network network, Instant at) {
        List<JourneyEvent> events = new ArrayList<>();
        for (Journey journey : journeys.values()) {
            if (journey.service == null) {
                continue;
            }
            Service service = service(network, journey.service);
            if (service == null) {
                changeService(journey, null, at, events);
            } else {
                relocate(journey, service);
            }
        }
        return events;
    }

    private static void relocate(Journey journey, Service service) {
        journey.approachAnnouncedFor = -1;
        if (journey.isVisiting()) {
            int index = locate(service, journey.platform.stationId(), -1);
            journey.lastIndex = index;
            journey.segmentStart = null;
            journey.offRouteVisit = index < 0;
            journey.phase = index >= 0 && service.stop(index).stops() ? JourneyPhase.AT_STOP : JourneyPhase.PASSING;
            return;
        }
        int previous = journey.lastIndex;
        int index = journey.lastDepartedStation == null ? -1 : locate(service, journey.lastDepartedStation, -1);
        if (index < 0) {
            journey.lastIndex = -1;
            journey.segmentStart = null;
            journey.phase = JourneyPhase.IDLE;
            return;
        }
        journey.lastIndex = index;
        if (index != previous) {
            journey.segmentStart = null;
        }
        if (journey.phase == JourneyPhase.IN_TRANSIT || journey.phase == JourneyPhase.COMPLETED) {
            boolean ended = service.isTerminus(index) && service.continuesAs() == null;
            journey.phase = ended ? JourneyPhase.COMPLETED : JourneyPhase.IN_TRANSIT;
        }
    }

    public Optional<JourneySnapshot> snapshot(String trainId) {
        Journey journey = journeys.get(trainId);
        return journey == null ? Optional.empty() : Optional.of(journey.snapshot());
    }

    public Collection<JourneySnapshot> snapshots() {
        List<JourneySnapshot> result = new ArrayList<>(journeys.size());
        for (Journey journey : journeys.values()) {
            result.add(journey.snapshot());
        }
        return result;
    }

    public void forget(String trainId) {
        journeys.remove(trainId);
    }

    /** Drops journeys of trains that are not loaded and have not been seen since {@code cutoff}. */
    public int expire(Instant cutoff, Predicate<String> loaded) {
        int before = journeys.size();
        journeys.values().removeIf(journey -> journey.lastSeen.isBefore(cutoff) && !loaded.test(journey.trainId));
        return before - journeys.size();
    }

    /** Refreshes the last-seen time of a train that is loaded but not at a platform. */
    public void touch(String trainId, Instant at) {
        Journey journey = journeys.get(trainId);
        if (journey != null) {
            journey.lastSeen = at;
        }
    }

    public int size() {
        return journeys.size();
    }

    private void changeService(Journey journey, @Nullable Service service, Instant at, List<JourneyEvent> events) {
        ServiceKey from = journey.service;
        journey.service = service == null ? null : service.key();
        journey.approachAnnouncedFor = -1;
        journey.offRouteVisit = false;
        journey.segmentStart = null;
        journey.lastIndex = -1;
        if (journey.isVisiting()) {
            int index = service == null ? -1 : locate(service, journey.platform.stationId(), -1);
            journey.lastIndex = index;
            if (service == null) {
                journey.phase = JourneyPhase.AT_STOP;
            } else if (index < 0) {
                journey.phase = JourneyPhase.PASSING;
                journey.offRouteVisit = true;
            } else {
                journey.phase = service.stop(index).stops() ? JourneyPhase.AT_STOP : JourneyPhase.PASSING;
            }
        } else {
            journey.phase = JourneyPhase.IDLE;
        }
        if (!Objects.equals(from, journey.service)) {
            events.add(new JourneyEvent.ServiceChanged(journey.trainId, at, from, journey.service));
        }
    }

    private void enter(
            Journey journey,
            @Nullable Service service,
            StopObservation observation,
            Network network,
            List<JourneyEvent> events) {
        String stationId = observation.platform().stationId();
        if (journey.isVisiting() && journey.platform.stationId().equals(stationId)) {
            journey.occupied.add(observation.trigger());
            return;
        }
        if (journey.isVisiting()) {
            // The train reached another station without us seeing it leave the previous one.
            depart(journey, service, observation.at(), network, events);
            service = service(network, journey.service);
        }
        if ((journey.phase == JourneyPhase.IN_TRANSIT || journey.phase == JourneyPhase.COMPLETED)
                && stationId.equals(journey.lastDepartedStation)
                && journey.departedAt != null
                && Duration.between(journey.departedAt, observation.at()).compareTo(REENTRY_WINDOW) < 0) {
            // Back at the station it just left (another platform, or a short move): resume the visit.
            journey.resumed = true;
            journey.phaseAfterDeparture = journey.phase;
            journey.phase = journey.resumePhase;
            journey.platform = observation.platform();
            journey.occupied.clear();
            journey.occupied.add(observation.trigger());
            return;
        }

        journey.platform = observation.platform();
        journey.occupied.clear();
        journey.occupied.add(observation.trigger());
        journey.arrivedAt = observation.at();
        if (service == null) {
            journey.phase = JourneyPhase.AT_STOP;
            events.add(new JourneyEvent.UnassignedArrived(journey.trainId, observation.at(), observation.platform()));
            return;
        }

        int index = locate(service, stationId, journey.lastIndex);
        if (index < 0) {
            journey.phase = JourneyPhase.PASSING;
            journey.offRouteVisit = true;
            events.add(new JourneyEvent.Passed(
                    journey.trainId, observation.at(), service.key(), -1, observation.platform(), -1, null));
            return;
        }

        int fromIndex = -1;
        Duration travelTime = null;
        if (journey.lastIndex >= 0 && journey.segmentStart != null && service.nextIndex(journey.lastIndex) == index) {
            fromIndex = journey.lastIndex;
            travelTime = Duration.between(journey.segmentStart, observation.at());
        }
        journey.lastIndex = index;
        journey.segmentStart = null;
        if (service.stop(index).stops()) {
            journey.phase = JourneyPhase.AT_STOP;
            events.add(new JourneyEvent.Arrived(journey.trainId, observation.at(), service.key(), index,
                    observation.platform(), service.isTerminus(index), fromIndex, travelTime));
        } else {
            journey.phase = JourneyPhase.PASSING;
            events.add(new JourneyEvent.Passed(journey.trainId, observation.at(), service.key(), index,
                    observation.platform(), fromIndex, travelTime));
        }
    }

    private void leave(
            Journey journey,
            @Nullable Service service,
            StopObservation observation,
            Network network,
            List<JourneyEvent> events) {
        if (!journey.isVisiting() || !journey.platform.stationId().equals(observation.platform().stationId())) {
            return;
        }
        journey.occupied.remove(observation.trigger());
        if (journey.occupied.isEmpty()) {
            depart(journey, service, observation.at(), network, events);
        }
    }

    private void depart(Journey journey, @Nullable Service service, Instant at, Network network, List<JourneyEvent> events) {
        if (journey.resumed) {
            // The departure was announced and learned when the train first left; do not repeat it.
            journey.resumed = false;
            journey.platform = null;
            journey.occupied.clear();
            journey.offRouteVisit = false;
            journey.departedAt = at;
            journey.phase = journey.phaseAfterDeparture;
            if (journey.timedDeparture) {
                journey.segmentStart = at;
            }
            return;
        }
        PlatformRef platform = journey.platform;
        JourneyPhase visitPhase = journey.phase;
        journey.platform = null;
        journey.occupied.clear();
        journey.lastDepartedStation = platform.stationId();
        journey.resumePhase = visitPhase;
        journey.departedAt = at;
        journey.phase = JourneyPhase.IN_TRANSIT;
        journey.timedDeparture = false;

        if (service == null) {
            events.add(new JourneyEvent.UnassignedDeparted(journey.trainId, at, platform));
            return;
        }
        if (journey.offRouteVisit) {
            journey.offRouteVisit = false;
            return;
        }
        int index = journey.lastIndex;
        journey.segmentStart = at;
        journey.timedDeparture = true;
        if (visitPhase == JourneyPhase.PASSING) {
            return;
        }
        Duration dwell = journey.arrivedAt == null ? null : Duration.between(journey.arrivedAt, at);
        if (!service.isTerminus(index)) {
            events.add(new JourneyEvent.Departed(journey.trainId, at, service.key(), index, platform,
                    service.nextCallingIndex(index), dwell));
            return;
        }

        Service continuation = service(network, service.continuesAs());
        if (continuation == null) {
            journey.phase = JourneyPhase.COMPLETED;
            events.add(new JourneyEvent.Departed(journey.trainId, at, service.key(), index, platform, -1, dwell));
            events.add(new JourneyEvent.TripCompleted(journey.trainId, at, service.key()));
            return;
        }
        events.add(new JourneyEvent.TripCompleted(journey.trainId, at, service.key()));
        journey.service = continuation.key();
        journey.approachAnnouncedFor = -1;
        events.add(new JourneyEvent.ServiceChanged(journey.trainId, at, service.key(), continuation.key()));
        int start = locate(continuation, platform.stationId(), -1);
        if (start < 0) {
            journey.lastIndex = -1;
            journey.segmentStart = null;
            journey.phase = JourneyPhase.IDLE;
            return;
        }
        // No departure announcement here: trains often run into a siding and come back to another
        // platform of the same station before really leaving. The boss bar already shows the next stop.
        journey.lastIndex = start;
    }

    /**
     * Finds the stop for {@code stationId}, searching forward from {@code lastIndex}. A
     * non-circular service is searched again from the start, which represents a new trip.
     */
    static int locate(Service service, String stationId, int lastIndex) {
        int size = service.size();
        int index = lastIndex;
        for (int step = 0; step < size; step++) {
            index = service.nextIndex(index);
            if (index < 0) {
                break;
            }
            if (service.stop(index).stationId().equals(stationId)) {
                return index;
            }
        }
        if (!service.circular()) {
            for (int candidate = 0; candidate <= Math.min(lastIndex, size - 1); candidate++) {
                if (service.stop(candidate).stationId().equals(stationId)) {
                    return candidate;
                }
            }
        }
        return -1;
    }

    private static @Nullable Service service(Network network, @Nullable ServiceKey key) {
        return key == null ? null : network.service(key).orElse(null);
    }

    private static final class Journey {
        private final String trainId;
        private @Nullable ServiceKey service;
        private @Nullable ServiceKey lastResolved;
        private JourneyPhase phase = JourneyPhase.IDLE;
        private int lastIndex = -1;
        private @Nullable PlatformRef platform;
        private final Set<BlockPos> occupied = new HashSet<>();
        private boolean offRouteVisit;
        private @Nullable String lastDepartedStation;
        private JourneyPhase resumePhase = JourneyPhase.AT_STOP;
        /** Set while a visit resumed after an announced departure; the next departure is silent. */
        private boolean resumed;
        private JourneyPhase phaseAfterDeparture = JourneyPhase.IN_TRANSIT;
        private boolean timedDeparture;
        private @Nullable Instant arrivedAt;
        private @Nullable Instant departedAt;
        /** When the train left {@link #lastIndex}; cleared when that segment time is unusable. */
        private @Nullable Instant segmentStart;
        private int approachAnnouncedFor = -1;
        private Instant lastSeen = Instant.EPOCH;

        private Journey(String trainId) {
            this.trainId = trainId;
        }

        private boolean isVisiting() {
            return platform != null && (phase == JourneyPhase.AT_STOP || phase == JourneyPhase.PASSING);
        }

        private JourneySnapshot snapshot() {
            return new JourneySnapshot(trainId, service, phase, lastIndex,
                    isVisiting() ? platform : null, arrivedAt, departedAt, segmentStart, lastSeen);
        }
    }
}
