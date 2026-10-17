package dev.advancedtrains.atcs.control;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.network.Stop;
import dev.advancedtrains.atcs.operation.JourneyEvent;
import dev.advancedtrains.atcs.operation.JourneyPhase;
import dev.advancedtrains.atcs.operation.JourneySnapshot;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Function;

/**
 * What an {@code atcs station} sign does with a train: whether it stops there at all (an express
 * runs through), and when it may leave again: not before its dwell, not before the headway after
 * the previous train of its line, not while the train ahead has yet to reach the next station,
 * not before its timetabled minute, and, with even spacing, not while it is catching up the train
 * in front. A dispatcher can also hold a train by hand. Pure logic over the journeys ATCS already
 * tracks; the TrainCarts adapter turns the answers into actions.
 */
public final class StationControl {

    /** What happens to a train the sign cannot place on a service. */
    public enum Policy { STOP, PASS }

    public enum Verdict { STOP, PASS }

    /** Even spacing holds a train at most this long beyond its dwell, whatever the gaps say. */
    public static final double EVEN_MAX_HOLD_DEFAULT = 60;
    /** A timetabled departure may be up to this far off, so the hold limit covers it. */
    static final double TIMETABLE_HOLD_SECONDS = 3660;
    /** Even spacing ignores differences smaller than this. */
    static final double EVEN_TOLERANCE_SECONDS = 2;

    /**
     * Configured behaviour, from {@code control:} in config.yml.
     *
     * @param unknownTrains       trains without a service
     * @param offRouteTrains      trains whose service does not list the station at all
     * @param dwellSeconds        how long a train waits when the sign gives no time
     * @param headwaySeconds      minimum time between departures of one line from one platform; 0 for none
     * @param holdForSection      whether trains wait for the section ahead to be clear
     * @param maxHoldSeconds      after which a held train is let go regardless, so a stale journey can
     *                            never block a platform for good
     * @param evenSpacing         whether a train catching up the one in front is held so gaps equalise
     * @param evenMaxHoldSeconds  the most even spacing adds to a stop
     */
    public record Rules(
            Policy unknownTrains,
            Policy offRouteTrains,
            double dwellSeconds,
            double headwaySeconds,
            boolean holdForSection,
            double maxHoldSeconds,
            boolean evenSpacing,
            double evenMaxHoldSeconds) {
        public static final Rules DEFAULT = new Rules(Policy.STOP, Policy.PASS, 10, 0, false, 180, false, EVEN_MAX_HOLD_DEFAULT);

        public Rules(Policy unknownTrains, Policy offRouteTrains, double dwellSeconds, double headwaySeconds,
                     boolean holdForSection, double maxHoldSeconds) {
            this(unknownTrains, offRouteTrains, dwellSeconds, headwaySeconds, holdForSection, maxHoldSeconds, false, EVEN_MAX_HOLD_DEFAULT);
        }
    }

    public record Decision(Verdict verdict, String reason) {
        public boolean stops() {
            return verdict == Verdict.STOP;
        }
    }

    /**
     * How a train that stops is handled: the sign's options with the configured defaults filled in.
     *
     * @param departureMinutes minutes past the hour the train leaves at; empty when it leaves after its dwell
     */
    public record Plan(
            double dwellSeconds,
            Heading heading,
            boolean autoRoute,
            double offset,
            double launchDistance,
            double launchSpeed,
            double headwaySeconds,
            boolean holdForSection,
            double maxHoldSeconds,
            boolean evenSpacing,
            double evenMaxHoldSeconds,
            List<Integer> departureMinutes) {

        public Plan {
            departureMinutes = List.copyOf(departureMinutes);
        }

        public static Plan of(StopOptions options, Rules rules) {
            return new Plan(
                    options.hasDwell() ? options.dwellSeconds() : rules.dwellSeconds(),
                    options.heading(),
                    options.autoRoute(),
                    options.offset(),
                    options.launchDistance(),
                    options.launchSpeed(),
                    options.hasHeadway() ? options.headwaySeconds() : rules.headwaySeconds(),
                    options.holdForSection() != null ? options.holdForSection() : rules.holdForSection(),
                    rules.maxHoldSeconds(),
                    options.evenSpacing() != null ? options.evenSpacing() : rules.evenSpacing(),
                    rules.evenMaxHoldSeconds(),
                    options.departureMinutes());
        }

        /** The same stop, leaving the other way: a short turn. */
        public Plan withHeading(Heading newHeading) {
            return new Plan(dwellSeconds, newHeading, autoRoute, offset, launchDistance, launchSpeed, headwaySeconds,
                    holdForSection, maxHoldSeconds, evenSpacing, evenMaxHoldSeconds, departureMinutes);
        }

        public Plan withDepartureMinutes(List<Integer> minutes) {
            return new Plan(dwellSeconds, heading, autoRoute, offset, launchDistance, launchSpeed, headwaySeconds,
                    holdForSection, maxHoldSeconds, evenSpacing, evenMaxHoldSeconds, minutes);
        }

        public boolean hasTimetable() {
            return !departureMinutes.isEmpty();
        }

        /** Whether the train may have to wait beyond its dwell time. */
        public boolean mayHold() {
            return headwaySeconds > 0 || holdForSection || evenSpacing || hasTimetable();
        }

        /**
         * How long a train is held at most beyond its dwell: the configured limit, or long enough
         * for the next timetabled departure.
         */
        public double holdLimitSeconds() {
            return hasTimetable() ? Math.max(maxHoldSeconds, TIMETABLE_HOLD_SECONDS) : maxHoldSeconds;
        }
    }

    /** What the control asks the estimator for: how far other trains are from where it matters. */
    public interface Lookahead {
        /** Seconds until the train reaches the next station on its service, when known. */
        OptionalDouble untilNextStation(JourneySnapshot journey);

        /** Seconds until the train reaches the platform's station on its service, when it is heading there. */
        OptionalDouble untilArrivalAt(JourneySnapshot journey, PlatformRef platform);

        Lookahead NONE = new Lookahead() {
            @Override
            public OptionalDouble untilNextStation(JourneySnapshot journey) {
                return OptionalDouble.empty();
            }

            @Override
            public OptionalDouble untilArrivalAt(JourneySnapshot journey, PlatformRef platform) {
                return OptionalDouble.empty();
            }
        };

        static Lookahead of(Function<JourneySnapshot, OptionalDouble> untilNextStation) {
            return new Lookahead() {
                @Override
                public OptionalDouble untilNextStation(JourneySnapshot journey) {
                    return untilNextStation.apply(journey);
                }

                @Override
                public OptionalDouble untilArrivalAt(JourneySnapshot journey, PlatformRef platform) {
                    return OptionalDouble.empty();
                }
            };
        }
    }

    private final ZoneId zone;
    /** Last departure per platform and line, and per platform and service. */
    private final Map<String, Instant> departures = new HashMap<>();
    private final Map<String, Instant> serviceDepartures = new HashMap<>();
    /** Trains standing at a station sign, with the rule they stand under. */
    private final Map<String, Standing> standing = new HashMap<>();
    /** Trains a dispatcher holds, with the reason given. */
    private final Map<String, String> manualHolds = new HashMap<>();

    public StationControl() {
        this(ZoneId.systemDefault());
    }

    /** @param zone the clock timetables are read in */
    public StationControl(ZoneId zone) {
        this.zone = zone;
    }

    /** A train at an {@code atcs station} sign: where, under which plan, since when. */
    public record Standing(PlatformRef platform, Plan plan, Instant arrivedAt) {
    }

    /** The sign decided the train stops here under this plan. */
    public void standing(String trainId, PlatformRef platform, Plan plan, Instant now) {
        standing.put(trainId, new Standing(platform, plan, now));
    }

    public Optional<Standing> standingAt(String trainId) {
        return Optional.ofNullable(standing.get(trainId));
    }

    /** The train left its platform (or was removed); it stands under no rule any more. */
    public void left(String trainId) {
        standing.remove(trainId);
    }

    // ---------------------------------------------------------------- dispatcher

    /** Keeps the train at its next (or current) station sign until released. */
    public void holdByHand(String trainId, String reason) {
        manualHolds.put(trainId, reason == null || reason.isBlank() ? "held by the dispatcher" : reason);
    }

    public boolean releaseByHand(String trainId) {
        return manualHolds.remove(trainId) != null;
    }

    public Optional<String> manualHold(String trainId) {
        return Optional.ofNullable(manualHolds.get(trainId));
    }

    public Map<String, String> manualHolds() {
        return Map.copyOf(manualHolds);
    }

    // ---------------------------------------------------------------- how long

    /** As {@link #remainingStop(String, Collection, Instant, Lookahead)} with only the next-station lookahead. */
    public OptionalDouble remainingStop(String trainId, Collection<JourneySnapshot> journeys, Instant now,
                                        Function<JourneySnapshot, OptionalDouble> untilNextStationOf) {
        return remainingStop(trainId, journeys, now, Lookahead.of(untilNextStationOf));
    }

    /**
     * Seconds until the rule lets a standing train go: the rest of its dwell, then the headway
     * after the last departure of its line, then the time the train in front needs to reach its
     * next station, then its timetabled minute, then what even spacing asks; capped at the hold
     * limit. Empty when the train stands under no rule. A dispatcher's hold is open-ended.
     */
    public OptionalDouble remainingStop(String trainId, Collection<JourneySnapshot> journeys, Instant now, Lookahead lookahead) {
        Standing stand = standing.get(trainId);
        if (stand == null) {
            return OptionalDouble.empty();
        }
        Plan plan = stand.plan();
        double dwellLeft = Math.max(0, plan.dwellSeconds() - seconds(stand.arrivedAt(), now));
        double remaining = dwellLeft;
        ServiceKey mine = serviceOf(trainId, journeys);
        if (plan.headwaySeconds() > 0 && mine != null) {
            Instant last = departures.get(key(stand.platform(), mine.lineId()));
            if (last != null) {
                remaining = Math.max(remaining, plan.headwaySeconds() - seconds(last, now));
            }
        }
        if (plan.holdForSection()) {
            for (JourneySnapshot other : journeys) {
                if (other.trainId().equals(trainId) || other.phase() != JourneyPhase.IN_TRANSIT
                        || !stand.platform().equals(other.lastPlatform())) {
                    continue;
                }
                OptionalDouble ahead = lookahead.untilNextStation(other);
                if (ahead.isPresent()) {
                    remaining = Math.max(remaining, ahead.getAsDouble() + 0.5);
                }
            }
        }
        if (plan.hasTimetable()) {
            remaining = Math.max(remaining, untilTimetabledDeparture(stand, now));
        }
        OptionalDouble even = evenHold(trainId, stand, mine, journeys, now, lookahead);
        if (even.isPresent()) {
            remaining = Math.max(remaining, even.getAsDouble());
        }
        double capped = Math.min(remaining, Math.max(dwellLeft, plan.holdLimitSeconds()));
        if (manualHolds.containsKey(trainId)) {
            return OptionalDouble.of(Math.max(capped, 60));
        }
        return OptionalDouble.of(capped);
    }

    /**
     * Seconds until the timetable lets the train go: the first departure minute at or after the
     * end of its dwell, in the configured zone's clock; 0 when that moment has passed.
     */
    double untilTimetabledDeparture(Standing stand, Instant now) {
        Instant earliest = stand.arrivedAt().plusMillis(Math.round(stand.plan().dwellSeconds() * 1000));
        Instant slot = nextSlot(earliest, stand.plan().departureMinutes());
        return Math.max(0, seconds(now, slot));
    }

    /** The first moment at one of the minutes past the hour that is not before {@code earliest}. */
    Instant nextSlot(Instant earliest, List<Integer> minutes) {
        ZonedDateTime hour = earliest.atZone(zone).truncatedTo(ChronoUnit.HOURS);
        for (int offset = 0; offset < 48; offset++) {
            ZonedDateTime base = hour.plusHours(offset);
            for (int minute : minutes) {
                ZonedDateTime slot = base.withMinute(minute);
                if (!slot.toInstant().isBefore(earliest)) {
                    return slot.toInstant();
                }
            }
        }
        return earliest;
    }

    /**
     * How much longer than its dwell the train should stand so the gap ahead and the gap behind
     * come out even: half the difference between the time until the next train of its service
     * arrives here and the time since the previous one left. Empty without even spacing, without
     * a previous departure or without a train behind. Never more than the configured limit past
     * the dwell.
     */
    private OptionalDouble evenHold(String trainId, Standing stand, @Nullable ServiceKey mine, Collection<JourneySnapshot> journeys,
                                    Instant now, Lookahead lookahead) {
        Plan plan = stand.plan();
        if (!plan.evenSpacing() || mine == null) {
            return OptionalDouble.empty();
        }
        Instant previous = serviceDepartures.get(serviceKey(stand.platform(), mine));
        if (previous == null) {
            return OptionalDouble.empty();
        }
        double gapAhead = seconds(previous, now);
        OptionalDouble gapBehind = OptionalDouble.empty();
        for (JourneySnapshot other : journeys) {
            if (other.trainId().equals(trainId) || !mine.equals(other.service())
                    || other.phase() == JourneyPhase.COMPLETED || other.phase() == JourneyPhase.IDLE) {
                continue;
            }
            OptionalDouble until = lookahead.untilArrivalAt(other, stand.platform());
            if (until.isPresent() && (gapBehind.isEmpty() || until.getAsDouble() < gapBehind.getAsDouble())) {
                gapBehind = until;
            }
        }
        if (gapBehind.isEmpty()) {
            return OptionalDouble.empty();
        }
        double extra = (gapBehind.getAsDouble() - gapAhead) / 2;
        if (extra < EVEN_TOLERANCE_SECONDS) {
            return OptionalDouble.of(0);
        }
        double stood = seconds(stand.arrivedAt(), now);
        double allowance = Math.max(0, plan.dwellSeconds() + plan.evenMaxHoldSeconds() - stood);
        return OptionalDouble.of(Math.min(extra, allowance));
    }

    // ---------------------------------------------------------------- stop or pass

    /**
     * Decides from what the journey tracker made of the train's arrival. {@code events} are the
     * events the arrival produced; when it produced none (the sign's platform was already reported
     * by a trigger bound in network.yml, or the train is back within seconds of leaving) the
     * journey's current state decides instead.
     */
    public static Decision decide(PlatformRef platform, List<JourneyEvent> events, @Nullable JourneySnapshot journey,
                                  Network network, Rules rules) {
        return decide(platform, events, journey, network, rules, false);
    }

    /** As above; a train bound for the depot runs through every station on its way. */
    public static Decision decide(PlatformRef platform, List<JourneyEvent> events, @Nullable JourneySnapshot journey,
                                  Network network, Rules rules, boolean toDepot) {
        if (toDepot) {
            return new Decision(Verdict.PASS, "bound for the depot");
        }
        for (JourneyEvent event : events) {
            switch (event) {
                case JourneyEvent.Arrived arrived -> {
                    return new Decision(Verdict.STOP, "calls here (stop " + (arrived.stopIndex() + 1) + " of " + arrived.service() + ")");
                }
                case JourneyEvent.Passed passed when passed.stopIndex() >= 0 -> {
                    return new Decision(Verdict.PASS, "runs through (pass stop of " + passed.service() + ")");
                }
                case JourneyEvent.Passed passed -> {
                    return policy(rules.offRouteTrains(), passed.service() + " does not list this station");
                }
                case JourneyEvent.UnassignedArrived ignored -> {
                    return policy(rules.unknownTrains(), "no service");
                }
                default -> {
                }
            }
        }
        if (journey == null || journey.platform() == null || !journey.platform().stationId().equals(platform.stationId())) {
            return new Decision(Verdict.PASS, "not visiting this station");
        }
        ServiceKey key = journey.service();
        if (key == null) {
            return policy(rules.unknownTrains(), "no service");
        }
        Service service = network.service(key).orElse(null);
        if (journey.phase() == JourneyPhase.AT_STOP) {
            return new Decision(Verdict.STOP, "calls here (" + key + ")");
        }
        if (journey.phase() == JourneyPhase.PASSING && service != null && journey.lastIndex() >= 0
                && journey.lastIndex() < service.size()) {
            Stop stop = service.stop(journey.lastIndex());
            if (stop.pass() && stop.stationId().equals(platform.stationId())) {
                return new Decision(Verdict.PASS, "runs through (pass stop of " + key + ")");
            }
        }
        if (journey.phase() == JourneyPhase.PASSING) {
            return policy(rules.offRouteTrains(), key + " does not list this station");
        }
        return new Decision(Verdict.PASS, "not visiting this station");
    }

    private static Decision policy(Policy policy, String why) {
        return new Decision(policy == Policy.STOP ? Verdict.STOP : Verdict.PASS,
                why + "; such trains " + (policy == Policy.STOP ? "stop" : "run through"));
    }

    /** Remembers when a train of a line left a platform, for headways and spacing. */
    public void departed(JourneyEvent.Departed event) {
        departures.put(key(event.platform(), event.service().lineId()), event.at());
        serviceDepartures.put(serviceKey(event.platform(), event.service()), event.at());
        standing.remove(event.trainId());
    }

    public Optional<Instant> lastDeparture(PlatformRef platform, String lineId) {
        return Optional.ofNullable(departures.get(key(platform, lineId)));
    }

    // ---------------------------------------------------------------- why

    /** As {@link #holdReason(String, PlatformRef, Collection, Network, Plan, Instant, Lookahead)} without lookahead. */
    public Optional<String> holdReason(String trainId, PlatformRef platform, Collection<JourneySnapshot> journeys,
                                       Network network, Plan plan, Instant now) {
        return holdReason(trainId, platform, journeys, network, plan, now, Lookahead.NONE);
    }

    /**
     * Why the train must stay at the platform for now, or empty when it may leave.
     *
     * @param journeys every tracked journey, the train's own among them
     */
    public Optional<String> holdReason(String trainId, PlatformRef platform, Collection<JourneySnapshot> journeys,
                                       Network network, Plan plan, Instant now, Lookahead lookahead) {
        String byHand = manualHolds.get(trainId);
        if (byHand != null) {
            return Optional.of("held by the dispatcher: " + byHand);
        }
        if (plan.holdForSection()) {
            for (JourneySnapshot other : journeys) {
                if (other.trainId().equals(trainId) || other.phase() != JourneyPhase.IN_TRANSIT
                        || !platform.equals(other.lastPlatform())) {
                    continue;
                }
                return Optional.of(other.trainId() + " ahead has not reached " + nextStationName(other, network));
            }
        }
        ServiceKey mine = serviceOf(trainId, journeys);
        if (plan.headwaySeconds() > 0 && mine != null) {
            Instant last = departures.get(key(platform, mine.lineId()));
            if (last != null) {
                long remaining = Math.round(plan.headwaySeconds() - seconds(last, now));
                if (remaining > 0) {
                    return Optional.of("headway: " + remaining + " s after the last line " + mine.lineId() + " train");
                }
            }
        }
        Standing stand = standing.get(trainId);
        if (plan.hasTimetable()) {
            Standing timed = stand != null ? stand : new Standing(platform, plan, now);
            double until = untilTimetabledDeparture(timed, now);
            if (until > 0.5) {
                Instant slot = now.plusMillis(Math.round(until * 1000));
                ZonedDateTime at = slot.atZone(zone);
                return Optional.of("timetable: departs at " + String.format("%02d:%02d", at.getHour(), at.getMinute()));
            }
        }
        if (stand != null && plan.evenSpacing()) {
            OptionalDouble even = evenHold(trainId, stand, mine, journeys, now, lookahead);
            if (even.isPresent() && even.getAsDouble() > 0.5) {
                return Optional.of("even spacing: " + Math.round(even.getAsDouble()) + " s more so the trains spread out");
            }
        }
        return Optional.empty();
    }

    private static @Nullable ServiceKey serviceOf(String trainId, Collection<JourneySnapshot> journeys) {
        for (JourneySnapshot journey : journeys) {
            if (journey.trainId().equals(trainId)) {
                return journey.service();
            }
        }
        return null;
    }

    private static String nextStationName(JourneySnapshot journey, Network network) {
        ServiceKey key = journey.service();
        Service service = key == null ? null : network.service(key).orElse(null);
        if (service == null) {
            return "its next station";
        }
        int next = journey.phase() == JourneyPhase.COMPLETED ? -1 : service.nextIndex(journey.lastIndex());
        return next < 0 ? "its next station" : network.stationName(service.stop(next).stationId());
    }

    private static double seconds(Instant from, Instant to) {
        return Duration.between(from, to).toMillis() / 1000.0;
    }

    private static String key(PlatformRef platform, String lineId) {
        return platform + "|" + Objects.requireNonNull(lineId);
    }

    private static String serviceKey(PlatformRef platform, ServiceKey service) {
        return platform + "|" + service;
    }
}
