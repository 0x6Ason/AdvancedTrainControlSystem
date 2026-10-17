package dev.advancedtrains.atcs.control;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.operation.Departures;
import dev.advancedtrains.atcs.operation.EtaCalculator;
import dev.advancedtrains.atcs.operation.EtaContext;
import dev.advancedtrains.atcs.operation.JourneyEvent;
import dev.advancedtrains.atcs.operation.JourneyPhase;
import dev.advancedtrains.atcs.operation.JourneyTracker;
import dev.advancedtrains.atcs.operation.JourneySnapshot;
import dev.advancedtrains.atcs.operation.StopObservation;
import dev.advancedtrains.atcs.operation.TravelTimes;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import static dev.advancedtrains.atcs.TestNetworks.network;
import static dev.advancedtrains.atcs.TestNetworks.service;
import static dev.advancedtrains.atcs.TestNetworks.trigger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Even spacing, timetables, dispatcher holds and virtual blocks. */
class RegulationTest {
    private static final Instant T0 = Instant.parse("2026-10-17T10:00:00Z");
    private static final ServiceKey LOOP = ServiceKey.parse("5/cw");
    private static final PlatformRef P2 = new PlatformRef("s2", "1");

    private final Network network = network(3, service("5/cw", "s1:1", "s2:1", "s3:1").withCircular(true));
    private final JourneyTracker tracker = new JourneyTracker();
    private final StationControl control = new StationControl(ZoneOffset.UTC);

    private void enter(String train, String station, Instant at) {
        for (JourneyEvent event : tracker.observe(new StopObservation(train, new PlatformRef(station, "1"), trigger(station), true, at), LOOP, network)) {
            if (event instanceof JourneyEvent.Departed departed) {
                control.departed(departed);
            }
        }
    }

    private void leave(String train, String station, Instant at) {
        for (JourneyEvent event : tracker.observe(new StopObservation(train, new PlatformRef(station, "1"), trigger(station), false, at), LOOP, network)) {
            if (event instanceof JourneyEvent.Departed departed) {
                control.departed(departed);
            }
        }
    }

    /** The train behind reaches s2 in {@code seconds}; nothing else is known. */
    private static StationControl.Lookahead behindIn(double seconds) {
        return new StationControl.Lookahead() {
            @Override
            public OptionalDouble untilNextStation(JourneySnapshot journey) {
                return OptionalDouble.empty();
            }

            @Override
            public OptionalDouble untilArrivalAt(JourneySnapshot journey, PlatformRef platform) {
                return journey.trainId().equals("behind") && platform.equals(P2) ? OptionalDouble.of(seconds) : OptionalDouble.empty();
            }
        };
    }

    @Test
    void evenSpacingHoldsATrainThatIsCatchingUp() {
        StationControl.Plan plan = StationControl.Plan.of(StopOptions.parse("10s even"), StationControl.Rules.DEFAULT);
        assertTrue(plan.evenSpacing() && plan.mayHold());
        // The train in front left s2 at T0; this one arrives 40 s later; the one behind is 200 s away.
        enter("front", "s2", T0.minusSeconds(10));
        leave("front", "s2", T0);
        enter("behind", "s1", T0);
        leave("behind", "s1", T0.plusSeconds(5));
        enter("mine", "s2", T0.plusSeconds(40));
        control.standing("mine", P2, plan, T0.plusSeconds(40));
        Instant now = T0.plusSeconds(50);
        // Gap ahead 50 s, gap behind 200 s: hold (200 - 50) / 2 = 75 s, capped at the 60 s allowance.
        OptionalDouble remaining = control.remainingStop("mine", tracker.snapshots(), now, behindIn(200));
        assertEquals(60, remaining.getAsDouble(), 0.01, "dwell is over; even spacing asks for 75 s but 60 is the most");
        Optional<String> reason = control.holdReason("mine", P2, tracker.snapshots(), network, plan, now, behindIn(200));
        assertEquals(Optional.of("even spacing: 60 s more so the trains spread out"), reason);

        reason = control.holdReason("mine", P2, tracker.snapshots(), network, plan, T0.plusSeconds(100), behindIn(150));
        assertEquals(Optional.of("even spacing: 10 s more so the trains spread out"), reason, "10 s of the allowance left");
        assertEquals(Optional.empty(), control.holdReason("mine", P2, tracker.snapshots(), network, plan, T0.plusSeconds(111), behindIn(140)),
                "the allowance is used up");
        assertEquals(Optional.empty(), control.holdReason("mine", P2, tracker.snapshots(), network, plan, T0.plusSeconds(50), behindIn(40)),
                "the train behind is close: no hold");
        assertEquals(Optional.empty(), control.holdReason("mine", P2, tracker.snapshots(), network, plan, T0.plusSeconds(50), StationControl.Lookahead.NONE),
                "nothing known about the train behind: no hold");
        StationControl.Plan plain = StationControl.Plan.of(StopOptions.parse("10s"), StationControl.Rules.DEFAULT);
        assertFalse(plain.evenSpacing());
        control.standing("mine", P2, plain, T0.plusSeconds(40));
        assertEquals(10, control.remainingStop("mine", tracker.snapshots(), T0.plusSeconds(40), behindIn(200)).getAsDouble(), 0.01,
                "without even spacing only the dwell counts");
    }

    /**
     * The first live railway with even spacing on a circular line, 2026-10-17: two trains standing,
     * each one's hold depending on when the other arrives, and the server overflowing its stack.
     * The control and the estimator are wired here as the runtime wires them.
     */
    @Test
    void twoStandingTrainsOnALoopDoNotAskEachOtherForever() {
        StationControl.Plan plan = StationControl.Plan.of(StopOptions.parse("10s even"), StationControl.Rules.DEFAULT);
        PlatformRef p1 = new PlatformRef("s1", "1");
        Service loop = network.service(LOOP).orElseThrow();
        // The previous train of the service left both platforms 30 s before A and B arrived.
        control.departed(new JourneyEvent.Departed("x", T0.minusSeconds(30), LOOP, 0, p1, 1, null));
        control.departed(new JourneyEvent.Departed("x", T0.minusSeconds(30), LOOP, 1, P2, 2, null));
        enter("A", "s1", T0);
        control.standing("A", p1, plan, T0);
        enter("B", "s2", T0);
        control.standing("B", P2, plan, T0);
        Instant now = T0.plusSeconds(5);

        EtaCalculator[] eta = new EtaCalculator[1];
        StationControl.Lookahead lookahead = new StationControl.Lookahead() {
            @Override
            public OptionalDouble untilNextStation(JourneySnapshot journey) {
                return OptionalDouble.empty();
            }

            @Override
            public OptionalDouble untilArrivalAt(JourneySnapshot journey, PlatformRef platform) {
                int target = Departures.targetIndex(journey, loop, platform.stationId());
                if (target < 0) {
                    return OptionalDouble.empty();
                }
                if (journey.phase() == JourneyPhase.AT_STOP && journey.lastIndex() == target) {
                    return OptionalDouble.of(0);
                }
                return eta[0].secondsUntil(journey, loop, target, now);
            }
        };
        EtaContext context = new EtaContext() {
            @Override
            public OptionalDouble segmentProgress(String trainId, Service service, int fromIndex) {
                return OptionalDouble.empty();
            }

            @Override
            public boolean standing(String trainId) {
                return true;
            }

            @Override
            public OptionalDouble remainingStop(String trainId, Instant at) {
                return control.remainingStop(trainId, tracker.snapshots(), at, lookahead);
            }
        };
        // Every stretch of the loop takes 60 s (a prior; nothing is learned yet), trains stand 10 s.
        eta[0] = new EtaCalculator(new TravelTimes(0.3), 10, context, (service, fromIndex) -> OptionalDouble.of(60));

        // A asks when B arrives at s1; B's own hold asks when A arrives at s2, and that question is
        // settled with A's plain dwell (5 s left): A at s2 in 65 s, gap ahead 35 s, so B is kept
        // (65 - 35) / 2 = 15 s; B at s1 in 15 + 60 + 10 + 60 = 145 s, so A is kept (145 - 35) / 2 = 55 s.
        assertEquals(55, control.remainingStop("A", tracker.snapshots(), now, lookahead).getAsDouble(), 0.01);
        // Asked about B first, the roles swap: A's hold counts B's plain dwell, 5 + 130 = 135 s at s1,
        // so A is kept 50 s and reaches s2 in 110 s; B is kept (110 - 35) / 2 = 37.5 s.
        assertEquals(37.5, control.remainingStop("B", tracker.snapshots(), now, lookahead).getAsDouble(), 0.01);
        // Asked through the estimator the chain is one level shorter: A's own hold is the one that
        // counts B's plain dwell (135 s to s1, kept 50 s), then 60 s to s2.
        JourneySnapshot a = tracker.snapshot("A").orElseThrow();
        assertEquals(50 + 60, eta[0].secondsUntil(a, loop, 1, now).getAsDouble(), 0.01);
    }

    @Test
    void timetabledDeparturesWaitForTheMinute() {
        StationControl.Plan plan = StationControl.Plan.of(StopOptions.parse("10s at :00 :30"), StationControl.Rules.DEFAULT);
        assertTrue(plan.hasTimetable() && plan.mayHold());
        assertEquals(3660, plan.holdLimitSeconds(), 0.01, "a timetable hold may outlast the usual limit");
        Instant arrived = Instant.parse("2026-10-17T10:12:00Z");
        enter("mine", "s2", arrived);
        control.standing("mine", P2, plan, arrived);
        assertEquals(18 * 60 - 20, control.remainingStop("mine", tracker.snapshots(), arrived.plusSeconds(20), StationControl.Lookahead.NONE).getAsDouble(), 0.01,
                "leaves at 10:30");
        assertEquals(Optional.of("timetable: departs at 10:30"),
                control.holdReason("mine", P2, tracker.snapshots(), network, plan, arrived.plusSeconds(20), StationControl.Lookahead.NONE));
        assertEquals(Optional.empty(),
                control.holdReason("mine", P2, tracker.snapshots(), network, plan, Instant.parse("2026-10-17T10:30:00Z"), StationControl.Lookahead.NONE));
        Instant late = Instant.parse("2026-10-17T10:59:55Z");
        assertEquals(Instant.parse("2026-10-17T11:00:00Z"), control.nextSlot(late, List.of(0, 30)), "the hour rolls over");
        assertEquals(Instant.parse("2026-10-17T10:30:00Z"), control.nextSlot(Instant.parse("2026-10-17T10:30:00Z"), List.of(0, 30)),
                "exactly on the minute is on time");
        StopOptions quarter = StopOptions.parse("at /15");
        assertEquals(List.of(0, 15, 30, 45), quarter.departureMinutes());
        assertEquals(":00 :15 :30 :45", StopOptions.formatMinutes(quarter.departureMinutes()));
    }

    @Test
    void aDispatcherHoldLastsUntilReleased() {
        StationControl.Plan plan = StationControl.Plan.of(StopOptions.parse("10s"), StationControl.Rules.DEFAULT);
        enter("mine", "s2", T0);
        control.standing("mine", P2, plan, T0);
        control.holdByHand("mine", "track inspection");
        assertEquals(Optional.of("held by the dispatcher: track inspection"),
                control.holdReason("mine", P2, tracker.snapshots(), network, plan, T0.plusSeconds(500), StationControl.Lookahead.NONE));
        assertTrue(control.remainingStop("mine", tracker.snapshots(), T0.plusSeconds(500), StationControl.Lookahead.NONE).getAsDouble() >= 60,
                "boards show a wait rather than a departure");
        assertTrue(control.releaseByHand("mine"));
        assertFalse(control.releaseByHand("mine"));
        assertEquals(Optional.empty(), control.holdReason("mine", P2, tracker.snapshots(), network, plan, T0.plusSeconds(500), StationControl.Lookahead.NONE));
    }

    @Test
    void blocksHoldOneTrainAtATime() {
        BlockControl blocks = new BlockControl();
        assertEquals(Optional.empty(), blocks.tryEnter("tunnel", "a", T0));
        assertEquals(Optional.of("a"), blocks.tryEnter("tunnel", "b", T0.plusSeconds(5)));
        assertEquals(Optional.empty(), blocks.tryEnter("tunnel", "a", T0.plusSeconds(6)), "the train inside may ask again");
        assertTrue(blocks.occupied("tunnel", T0.plusSeconds(10)));
        assertFalse(blocks.exit("tunnel", "b"), "only the train inside frees it");
        assertTrue(blocks.exit("tunnel", "a"));
        assertEquals(Optional.empty(), blocks.tryEnter("tunnel", "b", T0.plusSeconds(20)));
        assertEquals(List.of("tunnel"), blocks.left("b"), "a removed train frees every block it held");
        assertEquals(0, blocks.size());
        blocks.tryEnter("tunnel", "ghost", T0);
        assertEquals(Optional.empty(), blocks.tryEnter("tunnel", "c", T0.plus(BlockControl.STALE).plusSeconds(1)),
                "an occupancy nobody cleared is not believed for ever");
        blocks.force("tunnel", "d", T0);
        assertEquals("d", blocks.occupant("tunnel").orElseThrow().trainId());
    }
}
