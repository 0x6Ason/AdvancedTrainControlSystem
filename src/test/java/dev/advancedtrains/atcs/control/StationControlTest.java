package dev.advancedtrains.atcs.control;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.operation.JourneyEvent;
import dev.advancedtrains.atcs.operation.JourneyPhase;
import dev.advancedtrains.atcs.operation.JourneySnapshot;
import dev.advancedtrains.atcs.operation.JourneyTracker;
import dev.advancedtrains.atcs.operation.StopObservation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static dev.advancedtrains.atcs.TestNetworks.network;
import static dev.advancedtrains.atcs.TestNetworks.service;
import static dev.advancedtrains.atcs.TestNetworks.trigger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StationControlTest {
    private static final Instant T0 = Instant.parse("2026-10-15T10:00:00Z");
    private static final ServiceKey LOCAL = ServiceKey.parse("a/local");
    private static final ServiceKey EXPRESS = ServiceKey.parse("a/express");

    private final Network network = network(4,
            service("a/local", "s1:1", "s2:1", "~s3:1", "s4:1"),
            service("a/express", "s1:1", "s4:1"));
    private final JourneyTracker tracker = new JourneyTracker();

    private List<JourneyEvent> enter(String train, ServiceKey service, String station, Instant at) {
        return tracker.observe(new StopObservation(train, new PlatformRef(station, "1"), trigger(station), true, at), service, network);
    }

    private List<JourneyEvent> leave(String train, ServiceKey service, String station, Instant at) {
        return tracker.observe(new StopObservation(train, new PlatformRef(station, "1"), trigger(station), false, at), service, network);
    }

    private StationControl.Decision decide(String train, String station, List<JourneyEvent> events) {
        return StationControl.decide(new PlatformRef(station, "1"), events, tracker.snapshot(train).orElse(null),
                network, StationControl.Rules.DEFAULT);
    }

    @Test
    void localTrainsStopAndExpressTrainsRunThrough() {
        assertTrue(decide("local", "s2", enter("local", LOCAL, "s2", T0)).stops());
        assertFalse(decide("local", "s3", enter("local", LOCAL, "s3", T0.plusSeconds(60))).stops(), "a ~ stop");
        assertFalse(decide("express", "s2", enter("express", EXPRESS, "s2", T0)).stops(), "not on the express route");
        assertTrue(decide("express", "s4", enter("express", EXPRESS, "s4", T0.plusSeconds(60))).stops());
    }

    @Test
    void policiesDecideTrainsWithoutARouteHere() {
        List<JourneyEvent> unknown = enter("stray", null, "s2", T0);
        assertTrue(decide("stray", "s2", unknown).stops(), "unknown trains stop by default");
        StationControl.Rules passUnknown = new StationControl.Rules(StationControl.Policy.PASS, StationControl.Policy.STOP, 10, 0, false, 180);
        assertFalse(StationControl.decide(new PlatformRef("s2", "1"), unknown, tracker.snapshot("stray").orElse(null), network, passUnknown).stops());
        List<JourneyEvent> offRoute = enter("express", EXPRESS, "s2", T0);
        assertTrue(StationControl.decide(new PlatformRef("s2", "1"), offRoute, tracker.snapshot("express").orElse(null), network, passUnknown).stops(),
                "off-route trains stop when configured so");
    }

    @Test
    void decidesFromTheJourneyWhenTheArrivalProducedNoEvents() {
        enter("local", LOCAL, "s2", T0);
        assertTrue(decide("local", "s2", List.of()).stops(), "already reported by a network.yml trigger");
        enter("express", EXPRESS, "s2", T0);
        assertFalse(decide("express", "s2", List.of()).stops());
        enter("local2", LOCAL, "s3", T0);
        assertFalse(decide("local2", "s3", List.of()).stops(), "a pass stop");
        assertFalse(decide("nobody", "s2", List.of()).stops(), "a train the tracker has never seen");
    }

    @Test
    void holdsForTheTrainAheadAndForTheHeadway() {
        StationControl control = new StationControl();
        PlatformRef platform = new PlatformRef("s2", "1");
        StationControl.Plan plan = StationControl.Plan.of(StopOptions.parse("gap 60 block"), StationControl.Rules.DEFAULT);
        assertTrue(plan.mayHold());

        enter("first", LOCAL, "s2", T0);
        for (JourneyEvent event : leave("first", LOCAL, "s2", T0.plusSeconds(10))) {
            if (event instanceof JourneyEvent.Departed departed) {
                control.departed(departed);
            }
        }
        enter("second", LOCAL, "s2", T0.plusSeconds(20));
        Optional<String> reason = control.holdReason("second", platform, tracker.snapshots(), network, plan, T0.plusSeconds(30));
        assertEquals(Optional.of("first ahead has not reached Station S3"), reason);

        enter("first", LOCAL, "s3", T0.plusSeconds(40));
        reason = control.holdReason("second", platform, tracker.snapshots(), network, plan, T0.plusSeconds(45));
        assertEquals(Optional.of("headway: 25 s after the last line a train"), reason, "section clear, headway not yet");

        reason = control.holdReason("second", platform, tracker.snapshots(), network, plan, T0.plusSeconds(70));
        assertEquals(Optional.empty(), reason, "60 s after the first train left");

        StationControl.Plan dwellOnly = StationControl.Plan.of(StopOptions.parse("10s"), StationControl.Rules.DEFAULT);
        assertFalse(dwellOnly.mayHold());
        assertEquals(Optional.empty(), control.holdReason("second", platform, tracker.snapshots(), network, dwellOnly, T0.plusSeconds(30)));
    }

    @Test
    void aTrainAheadOnAnotherPlatformDoesNotHold() {
        StationControl control = new StationControl();
        StationControl.Plan plan = StationControl.Plan.of(StopOptions.parse("block"), StationControl.Rules.DEFAULT);
        tracker.observe(new StopObservation("other", new PlatformRef("s2", "2"), trigger("s2", "2"), true, T0), LOCAL, network);
        tracker.observe(new StopObservation("other", new PlatformRef("s2", "2"), trigger("s2", "2"), false, T0.plusSeconds(5)), LOCAL, network);
        enter("mine", LOCAL, "s2", T0.plusSeconds(10));
        JourneySnapshot other = tracker.snapshot("other").orElseThrow();
        assertEquals(JourneyPhase.IN_TRANSIT, other.phase());
        assertEquals(Optional.empty(),
                control.holdReason("mine", new PlatformRef("s2", "1"), tracker.snapshots(), network, plan, T0.plusSeconds(15)));
    }

    @Test
    void fillsInConfiguredDefaults() {
        StationControl.Rules rules = new StationControl.Rules(StationControl.Policy.STOP, StationControl.Policy.PASS, 8, 45, true, 120);
        StationControl.Plan plan = StationControl.Plan.of(StopOptions.DEFAULT, rules);
        assertEquals(8, plan.dwellSeconds());
        assertEquals(45, plan.headwaySeconds());
        assertTrue(plan.holdForSection());
        assertEquals(120, plan.maxHoldSeconds());
        StationControl.Plan overridden = StationControl.Plan.of(StopOptions.parse("3 gap 0 noblock"), rules);
        assertEquals(3, overridden.dwellSeconds());
        assertEquals(0, overridden.headwaySeconds());
        assertFalse(overridden.holdForSection());
        assertFalse(overridden.mayHold());
        assertEquals(Duration.ofSeconds(3), Duration.ofMillis(Math.round(overridden.dwellSeconds() * 1000)));
    }
}
