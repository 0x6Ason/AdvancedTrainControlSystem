package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.network.Stop;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import static dev.advancedtrains.atcs.TestNetworks.network;
import static dev.advancedtrains.atcs.TestNetworks.platform;
import static dev.advancedtrains.atcs.TestNetworks.service;
import static dev.advancedtrains.atcs.TestNetworks.trigger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Priors from track distance, late trains, shared physical segments, untriggered stops and intervals. */
class EstimationRound2Test {
    private static final Instant T0 = Instant.parse("2026-10-16T10:00:00Z");
    private static final ServiceKey KEY = ServiceKey.parse("a/north");
    private static final PlatformRef P1 = new PlatformRef("s1", "1");
    private static final PlatformRef P2 = new PlatformRef("s2", "1");
    private static final PlatformRef P3 = new PlatformRef("s3", "1");

    private final Service service = service("a/north", "s1", "s2", "s3");
    private final Network network = network(3, service);
    private final TravelTimes times = new TravelTimes(0.3);

    private void learnSegment(int from, int seconds) {
        times.record(new JourneyEvent.Arrived("x", T0, KEY, from + 1, platform("s" + (from + 2)), false, from,
                Duration.ofSeconds(seconds)), network);
    }

    private static JourneySnapshot inTransit(String train, int lastIndex, PlatformRef lastPlatform, Instant departedAt, Instant now) {
        return new JourneySnapshot(train, KEY, JourneyPhase.IN_TRANSIT, lastIndex, null, lastPlatform, -1,
                departedAt.minusSeconds(10), departedAt, departedAt, now);
    }

    @Test
    void unlearnedSegmentsAreGuessedFromTrackDistanceAndMarked() {
        learnSegment(0, 48);
        EtaCalculator.Priors priors = (s, index) -> index == 1 ? OptionalDouble.of(80) : OptionalDouble.empty();
        EtaCalculator eta = new EtaCalculator(times, 10, EtaContext.NONE, priors);
        Instant now = T0.plusSeconds(8);
        JourneySnapshot train = inTransit("t", 0, P1, T0, now);
        Estimator.Estimate toS2 = eta.estimate(train, service, 1, now).orElseThrow();
        assertEquals(40, toS2.seconds(), 0.01);
        assertFalse(toS2.approximate(), "learned all the way");
        Estimator.Estimate toS3 = eta.estimate(train, service, 2, now).orElseThrow();
        assertEquals(40 + 10 + 80, toS3.seconds(), 0.01);
        assertTrue(toS3.approximate(), "the last stretch is a guess");
        assertTrue(new EtaCalculator(times, 10).estimate(train, service, 2, now).isEmpty(), "without priors nothing is shown");
        JourneySnapshot onGuess = inTransit("u", 1, P2, T0, now);
        Estimator.Estimate guessed = eta.estimate(onGuess, service, 2, now).orElseThrow();
        assertEquals(72, guessed.seconds(), 0.01, "a guess counts down by the clock too");
        assertTrue(guessed.approximate());
        assertFalse(guessed.late(), "a guess is never called late");
    }

    @Test
    void aTrainPastItsUsualTimeIsLate() {
        learnSegment(0, 48);
        learnSegment(0, 50);
        learnSegment(0, 46);
        EtaCalculator eta = new EtaCalculator(times, 10);
        double usual = times.segmentSeconds(service, 0).orElseThrow();
        assertTrue(times.segmentSpread(service, 0).orElseThrow() < 5, "the runs agree");
        assertFalse(eta.estimate(inTransit("t", 0, P1, T0, T0.plusSeconds((long) usual + 5)), service, 1, T0.plusSeconds((long) usual + 5))
                .orElseThrow().late(), "a few seconds over is within the margin");
        Estimator.Estimate overdue = eta.estimate(inTransit("t", 0, P1, T0, T0.plusSeconds((long) usual + 15)), service, 1,
                T0.plusSeconds((long) usual + 15)).orElseThrow();
        assertTrue(overdue.late(), "well past the usual time with a tight spread");
        assertEquals(0, overdue.seconds(), 0.01);
        assertTrue(ConsistentEstimates.compute(List.of(inTransit("t", 0, P1, T0, T0.plusSeconds(70))), network, eta, T0.plusSeconds(70))
                .estimate(inTransit("t", 0, P1, T0, T0.plusSeconds(70)), service, 1, T0.plusSeconds(70)).orElseThrow().late(),
                "the flag survives the ordering pass");
        TravelTimes loose = new TravelTimes(0.3);
        for (int seconds : new int[] {30, 70, 40, 60}) {
            loose.record(new JourneyEvent.Arrived("x", T0, KEY, 1, P2, false, 0, Duration.ofSeconds(seconds)), network);
        }
        EtaCalculator looseEta = new EtaCalculator(loose, 10);
        double looseUsual = loose.segmentSeconds(service, 0).orElseThrow();
        assertFalse(looseEta.estimate(inTransit("t", 0, P1, T0, T0.plusSeconds((long) looseUsual + 15)), service, 1,
                T0.plusSeconds((long) looseUsual + 15)).orElseThrow().late(), "a wide spread allows more");
    }

    @Test
    void servicesOnTheSameTrackShareWhatOneLearned() {
        learnSegment(0, 48);
        Service express = Service.of(ServiceKey.parse("b/express"), "Express", List.of(Stop.parse("s1:1"), Stop.parse("s2:1"), Stop.of("s3")));
        Network both = network.toBuilder().line(Line.of("b", "Line B", 0x1E88E5).withService(express)).build();
        assertEquals(48, times.segmentSeconds(express, 0).orElseThrow(), 0.01, "learned by line a on the same platforms");
        assertTrue(times.ownSegmentSeconds(express, 0).isEmpty());
        assertTrue(times.segmentSeconds(express, 1).isEmpty(), "nobody has run s2 to s3");
        Service other = Service.of(ServiceKey.parse("b/other"), "Other", List.of(Stop.parse("s1:2"), Stop.parse("s2:2")));
        assertEquals(48, times.segmentSeconds(other, 0).orElseThrow(), 0.01, "other platforms: the station pair still helps");
        times.record(new JourneyEvent.Arrived("y", T0, express.key(), 1, P2, false, 0, Duration.ofSeconds(60)), both);
        assertEquals(60, times.segmentSeconds(express, 0).orElseThrow(), 0.01, "own data wins once there is some");
        YamlConfiguration yaml = new YamlConfiguration();
        times.save(yaml);
        TravelTimes loaded = new TravelTimes(0.3);
        loaded.load(yaml);
        assertEquals(times.segmentSeconds(other, 0), loaded.segmentSeconds(other, 0), "shared values are saved");
        assertEquals(times.segmentSpread(service, 0).isPresent(), loaded.segmentSpread(service, 0).isPresent());
    }

    @Test
    void anArrivalPastStopsWithoutTriggersIsSharedOutByDistance() {
        times.setMeasure((s, index) -> index == 0 ? OptionalDouble.of(60) : index == 1 ? OptionalDouble.of(40) : OptionalDouble.empty());
        times.setAssumedDwell(10);
        times.record(new JourneyEvent.Arrived("t", T0, KEY, 2, P3, true, 0, Duration.ofSeconds(110)), network);
        assertEquals(60, times.segmentSeconds(service, 0).orElseThrow(), 0.01, "100 s of running, 60 % of the track");
        assertEquals(40, times.segmentSeconds(service, 1).orElseThrow(), 0.01);
        TravelTimes unmeasured = new TravelTimes(0.3);
        unmeasured.record(new JourneyEvent.Arrived("t", T0, KEY, 2, P3, true, 0, Duration.ofSeconds(110)), network);
        assertEquals(50, unmeasured.segmentSeconds(service, 0).orElseThrow(), 0.01, "without lengths the time is split evenly");
        assertEquals(3, JourneyTracker.stepsAhead(service, 0, 2) + JourneyTracker.stepsAhead(service, 0, 1));
        assertEquals(0, JourneyTracker.stepsAhead(service, 2, 0), "not past the end of a linear service");
    }

    @Test
    void theTrackerKeepsTheTimeOfAnArrivalAFewStopsOn() {
        JourneyTracker tracker = new JourneyTracker();
        Instant now = T0;
        tracker.observe(StopObservation.enter("t", P1, trigger("s1"), now), KEY, network);
        now = now.plusSeconds(10);
        tracker.observe(StopObservation.leave("t", P1, trigger("s1"), now), KEY, network);
        now = now.plusSeconds(100);
        List<JourneyEvent> events = tracker.observe(StopObservation.enter("t", P3, trigger("s3"), now), KEY, network);
        JourneyEvent.Arrived arrived = events.stream().filter(JourneyEvent.Arrived.class::isInstance)
                .map(JourneyEvent.Arrived.class::cast).findFirst().orElseThrow();
        assertEquals(2, arrived.stopIndex());
        assertEquals(0, arrived.fromIndex(), "s2 never reported: the run from s1 still counts");
        assertEquals(Duration.ofSeconds(100), arrived.travelTime());
    }

    @Test
    void aSkippedStopIsRunThroughOnce() {
        JourneyTracker tracker = new JourneyTracker();
        assertFalse(tracker.skip("t", "s2"), "unknown trains cannot be told");
        tracker.observe(StopObservation.enter("t", P1, trigger("s1"), T0), KEY, network);
        tracker.observe(StopObservation.leave("t", P1, trigger("s1"), T0.plusSeconds(10)), KEY, network);
        assertTrue(tracker.skip("t", "s2"));
        assertEquals(java.util.Set.of("s2"), tracker.skips("t"));
        List<JourneyEvent> events = tracker.observe(StopObservation.enter("t", P2, trigger("s2"), T0.plusSeconds(60)), KEY, network);
        JourneyEvent.Passed passed = events.stream().filter(JourneyEvent.Passed.class::isInstance).map(JourneyEvent.Passed.class::cast)
                .findFirst().orElseThrow(() -> new AssertionError("expected a pass: " + events));
        assertEquals(1, passed.stopIndex());
        assertEquals(JourneyPhase.PASSING, tracker.snapshot("t").orElseThrow().phase());
        assertTrue(tracker.skips("t").isEmpty(), "once");
        assertFalse(tracker.unskip("t", "s2"));
        assertTrue(tracker.skip("t", "s3"));
        tracker.assign("t", ServiceKey.parse("b/other"), network.toBuilder()
                .line(Line.of("b", "Line B", 0x1E88E5).withService(service("b/other", "s3", "s2", "s1"))).build(), T0.plusSeconds(90));
        assertTrue(tracker.skips("t").isEmpty(), "a new run starts without skips");
    }

    @Test
    void intervalsBetweenDeparturesAreLearned() {
        times.record(new JourneyEvent.Departed("t1", T0, KEY, 0, P1, 1, Duration.ofSeconds(10)), network);
        assertTrue(times.intervalSeconds(service, 0).isEmpty(), "one departure is no interval");
        times.record(new JourneyEvent.Departed("t2", T0.plusSeconds(120), KEY, 0, P1, 1, Duration.ofSeconds(10)), network);
        assertEquals(120, times.intervalSeconds(service, 0).orElseThrow(), 0.01);
        times.record(new JourneyEvent.Departed("t3", T0.plusSeconds(3 * 3600), KEY, 0, P1, 1, null), network);
        assertEquals(120, times.intervalSeconds(service, 0).orElseThrow(), 0.01, "hours later is a gap, not an interval");
        YamlConfiguration yaml = new YamlConfiguration();
        times.save(yaml);
        TravelTimes loaded = new TravelTimes(0.3);
        loaded.load(yaml);
        assertEquals(120, loaded.intervalSeconds(service, 0).orElseThrow(), 0.01);
        Optional<Estimator.Estimate> none = new EtaCalculator(times, 10).estimate(inTransit("t", 0, P1, T0, T0), service, 1, T0);
        assertTrue(none.isEmpty(), "intervals say nothing about travel time");
    }
}
