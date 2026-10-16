package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalDouble;

import static dev.advancedtrains.atcs.TestNetworks.network;
import static dev.advancedtrains.atcs.TestNetworks.service;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The estimator with context: positions, standing trains, controlled stops, and trains kept in order. */
class EstimatesTest {
    private static final Instant T0 = Instant.parse("2026-10-16T10:00:00Z");
    private static final ServiceKey KEY = ServiceKey.parse("a/north");
    private static final PlatformRef P1 = new PlatformRef("s1", "1");
    private static final PlatformRef P2 = new PlatformRef("s2", "1");
    private static final PlatformRef P3 = new PlatformRef("s3", "1");

    private final Service service = service("a/north", "s1", "s2", "s3");
    private final Network network = network(3, service);
    private final TravelTimes times = learned();

    /** s1 to s2 takes 48 s, s2 to s3 60 s, trains stand 10 s. */
    private TravelTimes learned() {
        TravelTimes learned = new TravelTimes(0.3);
        learned.record(new JourneyEvent.Arrived("x", T0, KEY, 0, P1, false, -1, null), network);
        learned.record(new JourneyEvent.Departed("x", T0.plusSeconds(10), KEY, 0, P1, 1, Duration.ofSeconds(10)), network);
        learned.record(new JourneyEvent.Arrived("x", T0.plusSeconds(58), KEY, 1, P2, false, 0, Duration.ofSeconds(48)), network);
        learned.record(new JourneyEvent.Departed("x", T0.plusSeconds(68), KEY, 1, P2, 2, Duration.ofSeconds(10)), network);
        learned.record(new JourneyEvent.Arrived("x", T0.plusSeconds(128), KEY, 2, P3, true, 1, Duration.ofSeconds(60)), network);
        return learned;
    }

    private static JourneySnapshot inTransit(String train, int lastIndex, PlatformRef lastPlatform, Instant departedAt, Instant now) {
        return new JourneySnapshot(train, KEY, JourneyPhase.IN_TRANSIT, lastIndex, null, lastPlatform, -1,
                departedAt.minusSeconds(10), departedAt, departedAt, now);
    }

    private static JourneySnapshot atStop(String train, int index, PlatformRef platform, Instant arrivedAt, Instant now) {
        return new JourneySnapshot(train, KEY, JourneyPhase.AT_STOP, index, platform, platform, -1, arrivedAt, null, null, now);
    }

    @Test
    void positionOnThePathReplacesTheClock() {
        Instant now = T0.plusSeconds(1000);
        JourneySnapshot slow = inTransit("slow", 0, P1, now.minusSeconds(40), now);
        EtaCalculator byClock = new EtaCalculator(times, 10);
        assertEquals(8, byClock.secondsUntil(slow, service, 1, now).getAsDouble(), 0.01, "48 s learned, 40 s gone");
        EtaContext halfway = context(0.5, false, OptionalDouble.empty());
        EtaCalculator byPosition = byClock.withContext(halfway);
        assertEquals(24, byPosition.secondsUntil(slow, service, 1, now).getAsDouble(), 0.01, "only half the path is done");
        assertEquals(24 + 10 + 60, byPosition.secondsUntil(slow, service, 2, now).getAsDouble(), 0.01);
        EtaCalculator onTime = byClock.withContext(context(0.9, false, OptionalDouble.empty()));
        assertEquals(8, onTime.secondsUntil(slow, service, 1, now).getAsDouble(), 0.01,
                "a train further along than the clock suggests keeps the clock's estimate");
    }

    @Test
    void aStandingTrainKeepsItsEstimate() {
        Instant now = T0.plusSeconds(1000);
        JourneySnapshot stuck = inTransit("stuck", 0, P1, now.minusSeconds(100), now);
        EtaCalculator eta = new EtaCalculator(times, 10, context(0.5, true, OptionalDouble.empty()));
        assertEquals(24, eta.secondsUntil(stuck, service, 1, now).getAsDouble(), 0.01);
        TrainView view = TrainView.of(stuck, network, times, eta, context(0.5, true, OptionalDouble.empty()), now).orElseThrow();
        assertTrue(view.held());
        EtaCalculator noPath = new EtaCalculator(times, 10, context(-1, true, OptionalDouble.empty()));
        assertEquals(5, noPath.secondsUntil(stuck, service, 1, now).getAsDouble(), 0.01, "without a path the countdown floors instead of reaching Arriving");
    }

    @Test
    void aControlledStopUsesTheRuleNotTheLearnedDwell() {
        Instant now = T0.plusSeconds(1000);
        JourneySnapshot waiting = atStop("w", 1, P2, now.minusSeconds(3), now);
        EtaCalculator learnedDwell = new EtaCalculator(times, 10);
        assertEquals(7 + 60, learnedDwell.secondsUntil(waiting, service, 2, now).getAsDouble(), 0.01);
        EtaCalculator controlled = learnedDwell.withContext(context(-1, false, OptionalDouble.of(42)));
        assertEquals(42 + 60, controlled.secondsUntil(waiting, service, 2, now).getAsDouble(), 0.01);
    }

    @Test
    void aFollowingTrainIsKeptBehindTheOneInFront() {
        Instant now = T0.plusSeconds(1000);
        JourneySnapshot ahead = atStop("A", 1, P2, now, now);                              // leaves s2 in 10 s
        JourneySnapshot behind = inTransit("B", 0, P1, now.minusSeconds(40), now);         // plain estimate: s2 in 8 s
        EtaCalculator plain = new EtaCalculator(times, 10);
        assertEquals(8, plain.secondsUntil(behind, service, 1, now).getAsDouble(), 0.01);
        ConsistentEstimates consistent = ConsistentEstimates.compute(List.of(ahead, behind), network, plain, now);
        assertEquals(15, consistent.secondsUntil(behind, service, 1, now).getAsDouble(), 0.01,
                "not before A has left (10 s) plus the separation (5 s)");
        assertEquals(15 + 10 + 60, consistent.secondsUntil(behind, service, 2, now).getAsDouble(), 0.01, "and every later stop moves with it");
        assertEquals(0, consistent.secondsUntil(ahead, service, 1, now).getAsDouble(), 0.01);
        assertEquals(70, consistent.secondsUntil(ahead, service, 2, now).getAsDouble(), 0.01, "the train in front is unchanged");
        JourneySnapshot other = new JourneySnapshot("C", ServiceKey.parse("b/x"), JourneyPhase.IN_TRANSIT, 0, null, P1, -1,
                now, now, now, now);
        assertTrue(consistent.secondsUntil(other, service, 1, now).isPresent(), "an unknown train falls back to the plain estimate");
    }

    @Test
    void progressAlongARecordedPath() {
        TrackPaths paths = new TrackPaths();
        paths.onEvent(new JourneyEvent.Departed("t", T0, KEY, 0, P1, 1, null), network);
        paths.sample("t", "world", 0, 64, 0);
        paths.sample("t", "world", 100, 64, 0);
        paths.sample("t", "world", 100, 64, 50);
        paths.onEvent(new JourneyEvent.Arrived("t", T0.plusSeconds(30), KEY, 1, P2, false, 0, Duration.ofSeconds(30)), network);
        assertEquals(50.0 / 150, paths.progress(service, 0, "world", 50, 64, 1).getAsDouble(), 0.001);
        assertEquals(125.0 / 150, paths.progress(service, 0, "world", 101, 65, 25).getAsDouble(), 0.001);
        assertTrue(paths.progress(service, 0, "world", 50, 64, 30).isEmpty(), "too far from the track");
        assertTrue(paths.progress(service, 0, "nether", 50, 64, 1).isEmpty(), "another world");
        assertTrue(paths.progress(service, 1, "world", 50, 64, 1).isEmpty(), "segment not recorded");
    }

    private static EtaContext context(double progress, boolean standing, OptionalDouble remaining) {
        return new EtaContext() {
            @Override
            public OptionalDouble segmentProgress(String trainId, Service service, int fromIndex) {
                return progress < 0 ? OptionalDouble.empty() : OptionalDouble.of(progress);
            }

            @Override
            public boolean standing(String trainId) {
                return standing;
            }

            @Override
            public OptionalDouble remainingStop(String trainId, Instant now) {
                return remaining;
            }
        };
    }
}
