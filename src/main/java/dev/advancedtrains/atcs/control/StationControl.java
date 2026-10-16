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
 * runs through), and when it may leave again (not before the headway after the previous train of
 * its line, and not while the train ahead has yet to reach the next station). Pure logic over the
 * journeys ATCS already tracks; the TrainCarts adapter turns the answers into actions.
 */
public final class StationControl {

    /** What happens to a train the sign cannot place on a service. */
    public enum Policy { STOP, PASS }

    public enum Verdict { STOP, PASS }

    /**
     * Configured behaviour, from {@code control:} in config.yml.
     *
     * @param unknownTrains  trains without a service
     * @param offRouteTrains trains whose service does not list the station at all
     * @param dwellSeconds   how long a train waits when the sign gives no time
     * @param headwaySeconds minimum time between departures of one line from one platform; 0 for none
     * @param holdForSection whether trains wait for the section ahead to be clear
     * @param maxHoldSeconds after which a held train is let go regardless, so a stale journey can
     *                       never block a platform for good
     */
    public record Rules(
            Policy unknownTrains,
            Policy offRouteTrains,
            double dwellSeconds,
            double headwaySeconds,
            boolean holdForSection,
            double maxHoldSeconds) {
        public static final Rules DEFAULT = new Rules(Policy.STOP, Policy.PASS, 10, 0, false, 180);
    }

    public record Decision(Verdict verdict, String reason) {
        public boolean stops() {
            return verdict == Verdict.STOP;
        }
    }

    /**
     * How a train that stops is handled: the sign's options with the configured defaults filled in.
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
            double maxHoldSeconds) {

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
                    rules.maxHoldSeconds());
        }

        /** Whether the train may have to wait beyond its dwell time. */
        public boolean mayHold() {
            return headwaySeconds > 0 || holdForSection;
        }
    }

    private final Map<String, Instant> departures = new HashMap<>();
    /** Trains standing at a station sign, with the rule they stand under. */
    private final Map<String, Standing> standing = new HashMap<>();

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

    /**
     * Seconds until the rule lets a standing train go: the rest of its dwell, then the headway
     * after the last departure of its line, then the time the train in front needs to reach its
     * next station (asked through {@code untilNextStationOf}), capped at the maximum hold. Empty
     * when the train stands under no rule.
     */
    public OptionalDouble remainingStop(String trainId, Collection<JourneySnapshot> journeys, Instant now,
                                        Function<JourneySnapshot, OptionalDouble> untilNextStationOf) {
        Standing stand = standing.get(trainId);
        if (stand == null) {
            return OptionalDouble.empty();
        }
        Plan plan = stand.plan();
        double dwellLeft = Math.max(0, plan.dwellSeconds() - Duration.between(stand.arrivedAt(), now).toMillis() / 1000.0);
        double remaining = dwellLeft;
        String lineId = null;
        for (JourneySnapshot journey : journeys) {
            if (journey.trainId().equals(trainId) && journey.service() != null) {
                lineId = journey.service().lineId();
            }
        }
        if (plan.headwaySeconds() > 0 && lineId != null) {
            Instant last = departures.get(key(stand.platform(), lineId));
            if (last != null) {
                remaining = Math.max(remaining, plan.headwaySeconds() - Duration.between(last, now).toMillis() / 1000.0);
            }
        }
        if (plan.holdForSection()) {
            for (JourneySnapshot other : journeys) {
                if (other.trainId().equals(trainId) || other.phase() != JourneyPhase.IN_TRANSIT
                        || !stand.platform().equals(other.lastPlatform())) {
                    continue;
                }
                OptionalDouble ahead = untilNextStationOf.apply(other);
                if (ahead.isPresent()) {
                    remaining = Math.max(remaining, ahead.getAsDouble() + 0.5);
                }
            }
        }
        return OptionalDouble.of(Math.min(remaining, Math.max(dwellLeft, plan.maxHoldSeconds())));
    }

    /**
     * Decides from what the journey tracker made of the train's arrival. {@code events} are the
     * events the arrival produced; when it produced none (the sign's platform was already reported
     * by a trigger bound in network.yml, or the train is back within seconds of leaving) the
     * journey's current state decides instead.
     */
    public static Decision decide(PlatformRef platform, List<JourneyEvent> events, @Nullable JourneySnapshot journey,
                                  Network network, Rules rules) {
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

    /** Remembers when a train of a line left a platform, for headways. */
    public void departed(JourneyEvent.Departed event) {
        departures.put(key(event.platform(), event.service().lineId()), event.at());
        standing.remove(event.trainId());
    }

    public Optional<Instant> lastDeparture(PlatformRef platform, String lineId) {
        return Optional.ofNullable(departures.get(key(platform, lineId)));
    }

    /**
     * Why the train must stay at the platform for now, or empty when it may leave.
     *
     * @param journeys every tracked journey, the train's own among them
     */
    public Optional<String> holdReason(String trainId, PlatformRef platform, Collection<JourneySnapshot> journeys,
                                       Network network, Plan plan, Instant now) {
        if (plan.holdForSection()) {
            for (JourneySnapshot other : journeys) {
                if (other.trainId().equals(trainId) || other.phase() != JourneyPhase.IN_TRANSIT
                        || !platform.equals(other.lastPlatform())) {
                    continue;
                }
                return Optional.of(other.trainId() + " ahead has not reached " + nextStationName(other, network));
            }
        }
        if (plan.headwaySeconds() > 0) {
            String lineId = null;
            for (JourneySnapshot journey : journeys) {
                if (journey.trainId().equals(trainId) && journey.service() != null) {
                    lineId = journey.service().lineId();
                }
            }
            Instant last = lineId == null ? null : departures.get(key(platform, lineId));
            if (last != null) {
                Duration since = Duration.between(last, now);
                long remaining = Math.round(plan.headwaySeconds() - since.toMillis() / 1000.0);
                if (remaining > 0) {
                    return Optional.of("headway: " + remaining + " s after the last line " + lineId + " train");
                }
            }
        }
        return Optional.empty();
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

    private static String key(PlatformRef platform, String lineId) {
        return platform + "|" + Objects.requireNonNull(lineId);
    }
}
