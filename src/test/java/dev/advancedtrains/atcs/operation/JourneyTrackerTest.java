package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.operation.JourneyEvent.Approaching;
import dev.advancedtrains.atcs.operation.JourneyEvent.Arrived;
import dev.advancedtrains.atcs.operation.JourneyEvent.Departed;
import dev.advancedtrains.atcs.operation.JourneyEvent.Passed;
import dev.advancedtrains.atcs.operation.JourneyEvent.ServiceChanged;
import dev.advancedtrains.atcs.operation.JourneyEvent.TripCompleted;
import dev.advancedtrains.atcs.operation.JourneyEvent.UnassignedArrived;
import dev.advancedtrains.atcs.operation.JourneyEvent.UnassignedDeparted;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static dev.advancedtrains.atcs.TestNetworks.network;
import static dev.advancedtrains.atcs.TestNetworks.platform;
import static dev.advancedtrains.atcs.TestNetworks.service;
import static dev.advancedtrains.atcs.TestNetworks.trigger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JourneyTrackerTest {
    private static final ServiceKey A = ServiceKey.parse("a/north");
    private static final ServiceKey B = ServiceKey.parse("a/south");
    private static final String TRAIN = "train1";

    private final JourneyTracker tracker = new JourneyTracker();
    private Instant now = Instant.parse("2026-10-11T12:00:00Z");

    private List<JourneyEvent> enter(String station, ServiceKey resolved, Network network) {
        return enter(station, "1", resolved, network);
    }

    private List<JourneyEvent> enter(String station, String platform, ServiceKey resolved, Network network) {
        return tracker.observe(StopObservation.enter(TRAIN, new PlatformRef(station, platform),
                trigger(station, platform), now), resolved, network);
    }

    private List<JourneyEvent> leave(String station, ServiceKey resolved, Network network) {
        return tracker.observe(StopObservation.leave(TRAIN, platform(station), trigger(station), now),
                resolved, network);
    }

    private void advance(int seconds) {
        now = now.plusSeconds(seconds);
    }

    private static <T> T only(List<JourneyEvent> events, Class<T> type) {
        assertEquals(1, events.stream().filter(type::isInstance).count(), () -> "events: " + events);
        return events.stream().filter(type::isInstance).map(type::cast).findFirst().orElseThrow();
    }

    @Test
    void followsLinearServiceAndCompletesTrip() {
        Network network = network(3, service("a/north", "s1", "s2", "s3"));

        List<JourneyEvent> first = enter("s1", A, network);
        assertInstanceOf(ServiceChanged.class, first.get(0));
        Arrived arrivedFirst = only(first, Arrived.class);
        assertEquals(0, arrivedFirst.stopIndex());
        assertEquals(-1, arrivedFirst.fromIndex());

        advance(8);
        Departed departed = only(leave("s1", A, network), Departed.class);
        assertEquals(1, departed.nextIndex());
        assertEquals(Duration.ofSeconds(8), departed.dwell());

        advance(30);
        Arrived arrived = only(enter("s2", A, network), Arrived.class);
        assertEquals(1, arrived.stopIndex());
        assertEquals(0, arrived.fromIndex());
        assertEquals(Duration.ofSeconds(30), arrived.travelTime());

        advance(5);
        leave("s2", A, network);
        advance(40);
        Arrived terminus = only(enter("s3", A, network), Arrived.class);
        assertTrue(terminus.terminus());

        advance(5);
        List<JourneyEvent> end = leave("s3", A, network);
        assertEquals(-1, only(end, Departed.class).nextIndex());
        only(end, TripCompleted.class);
        assertEquals(JourneyPhase.COMPLETED, tracker.snapshot(TRAIN).orElseThrow().phase());
    }

    @Test
    void repeatedAndOverlappingTriggersFormOneVisit() {
        Network network = network(3, service("a/north", "s1", "s2", "s3"));
        enter("s1", A, network);
        assertTrue(enter("s1", A, network).isEmpty(), "same trigger again");
        assertTrue(enter("s1", "2", A, network).isEmpty(), "second trigger at the same station");

        assertTrue(leave("s1", A, network).isEmpty(), "still on platform 2's trigger");
        List<JourneyEvent> departure = tracker.observe(StopObservation.leave(TRAIN, new PlatformRef("s1", "2"),
                trigger("s1", "2"), now), A, network);
        only(departure, Departed.class);
    }

    @Test
    void passStopsAreSilentButCountForTimings() {
        Network network = network(3, service("a/north", "s1", "~s2", "s3"));
        enter("s1", A, network);
        leave("s1", A, network);
        advance(20);
        Passed passed = only(enter("s2", A, network), Passed.class);
        assertEquals(1, passed.stopIndex());
        assertEquals(Duration.ofSeconds(20), passed.travelTime());

        advance(2);
        assertTrue(leave("s2", A, network).isEmpty());
        advance(25);
        Arrived arrived = only(enter("s3", A, network), Arrived.class);
        assertEquals(1, arrived.fromIndex());
        assertEquals(Duration.ofSeconds(25), arrived.travelTime());
    }

    @Test
    void stationNotOnServiceIsPassedWithoutBreakingSegmentTiming() {
        Network network = network(3, service("a/express", "s1", "s3"));
        ServiceKey express = ServiceKey.parse("a/express");
        enter("s1", express, network);
        leave("s1", express, network);
        advance(15);
        Passed passed = only(enter("s2", express, network), Passed.class);
        assertEquals(-1, passed.stopIndex());
        advance(3);
        leave("s2", express, network);
        advance(12);
        Arrived arrived = only(enter("s3", express, network), Arrived.class);
        assertEquals(0, arrived.fromIndex());
        assertEquals(Duration.ofSeconds(30), arrived.travelTime());
    }

    @Test
    void skippedStopsStillKeepTheRunTime() {
        // A stop without triggers never reports; the run from the last reported stop is still measured.
        Network network = network(3, service("a/north", "s1", "s2", "s3"));
        enter("s1", A, network);
        leave("s1", A, network);
        advance(60);
        Arrived arrived = only(enter("s3", A, network), Arrived.class);
        assertEquals(2, arrived.stopIndex());
        assertEquals(0, arrived.fromIndex());
        assertEquals(Duration.ofSeconds(60), arrived.travelTime());
    }

    @Test
    void continuesAsReturnServiceAndIsNotUndoneByResolver() {
        Network network = network(3,
                service("a/north", "s1", "s2", "s3").withContinuesAs(B),
                service("a/south", "s3", "s2", "s1"));
        enter("s1", A, network);
        leave("s1", A, network);
        enter("s2", A, network);
        leave("s2", A, network);
        enter("s3", A, network);

        List<JourneyEvent> turn = leave("s3", A, network);
        only(turn, TripCompleted.class);
        ServiceChanged changed = only(turn, ServiceChanged.class);
        assertEquals(A, changed.from());
        assertEquals(B, changed.to());
        assertTrue(turn.stream().noneMatch(Departed.class::isInstance),
                "no departure announcement while the train may still be turning in a siding");
        JourneySnapshot turned = tracker.snapshot(TRAIN).orElseThrow();
        assertEquals(B, turned.service());
        assertEquals(0, turned.lastIndex());
        assertEquals(JourneyPhase.IN_TRANSIT, turned.phase());

        // The train's tags still name the northbound service; the switch must stick.
        Arrived arrived = only(enter("s2", A, network), Arrived.class);
        assertEquals(B, arrived.service());
        assertEquals(1, arrived.stopIndex());
    }

    @Test
    void circularServiceWrapsAround() {
        Network network = network(3, service("a/north", "s1", "s2", "s3").withCircular(true));
        enter("s1", A, network);
        leave("s1", A, network);
        enter("s2", A, network);
        leave("s2", A, network);
        enter("s3", A, network);
        assertEquals(0, only(leave("s3", A, network), Departed.class).nextIndex());
        advance(30);
        Arrived arrived = only(enter("s1", A, network), Arrived.class);
        assertEquals(0, arrived.stopIndex());
        assertEquals(2, arrived.fromIndex());
        assertEquals(Duration.ofSeconds(30), arrived.travelTime());
    }

    @Test
    void unassignedTrainsStillReportStations() {
        Network network = network(2);
        UnassignedArrived arrived = only(enter("s1", null, network), UnassignedArrived.class);
        assertEquals("s1", arrived.platform().stationId());
        only(leave("s1", null, network), UnassignedDeparted.class);
    }

    @Test
    void reachingNextStationWithoutLeaveImpliesDeparture() {
        Network network = network(3, service("a/north", "s1", "s2", "s3"));
        enter("s1", A, network);
        advance(30);
        List<JourneyEvent> events = enter("s2", A, network);
        assertInstanceOf(Departed.class, events.get(0));
        assertInstanceOf(Arrived.class, events.get(1));
    }

    @Test
    void reenteringJustLeftStationResumesVisitQuietly() {
        Network network = network(3, service("a/north", "s1", "s2", "s3"));
        enter("s1", A, network);
        leave("s1", A, network);
        advance(3);
        assertTrue(enter("s1", A, network).isEmpty());
        advance(5);
        assertTrue(leave("s1", A, network).isEmpty(), "the departure was announced when the train first left");
    }

    @Test
    void reenteringAfterWindowIsANewTrip() {
        Network network = network(3, service("a/north", "s1", "s2", "s3"));
        enter("s1", A, network);
        leave("s1", A, network);
        advance(120);
        Arrived arrived = only(enter("s1", A, network), Arrived.class);
        assertEquals(0, arrived.stopIndex());
    }

    @Test
    void serviceAssignedWhileStandingAtStationLocatesTheStop() {
        Network network = network(3, service("a/north", "s1", "s2", "s3"));
        enter("s2", null, network);
        List<JourneyEvent> events = leave("s2", A, network);
        only(events, ServiceChanged.class);
        Departed departed = only(events, Departed.class);
        assertEquals(1, departed.stopIndex());
    }

    @Test
    void approachIsAnnouncedOncePerStop() {
        Network network = network(3, service("a/north", "s1", "s2", "s3"));
        enter("s1", A, network);
        assertTrue(tracker.markApproaching(TRAIN, network, now).isEmpty(), "not while standing at a stop");
        leave("s1", A, network);
        Approaching approaching = (Approaching) tracker.markApproaching(TRAIN, network, now).orElseThrow();
        assertEquals(1, approaching.stopIndex());
        assertTrue(tracker.markApproaching(TRAIN, network, now).isEmpty());
        enter("s2", A, network);
        leave("s2", A, network);
        assertEquals(2, ((Approaching) tracker.markApproaching(TRAIN, network, now).orElseThrow()).stopIndex());
    }

    @Test
    void approachSkipsPassStops() {
        Network network = network(3, service("a/north", "s1", "~s2", "s3"));
        enter("s1", A, network);
        leave("s1", A, network);
        assertEquals(2, ((Approaching) tracker.markApproaching(TRAIN, network, now).orElseThrow()).stopIndex());
    }

    @Test
    void completedTripRestartsFromFirstStop() {
        Network network = network(3, service("a/north", "s1", "s2", "s3"));
        enter("s1", A, network);
        leave("s1", A, network);
        enter("s3", A, network);
        leave("s3", A, network);
        advance(60);
        assertEquals(0, only(enter("s1", A, network), Arrived.class).stopIndex());
    }

    @Test
    void removedServiceFallsBackToUnassigned() {
        Network withService = network(3, service("a/north", "s1", "s2", "s3"));
        enter("s1", A, withService);
        leave("s1", A, withService);
        Network without = network(3);
        List<JourneyEvent> events = enter("s2", null, without);
        ServiceChanged changed = only(events, ServiceChanged.class);
        assertEquals(A, changed.from());
        assertNull(changed.to());
        only(events, UnassignedArrived.class);
    }

    @Test
    void explicitAssignmentWins() {
        Network network = network(3, service("a/north", "s1", "s2", "s3"), service("a/south", "s3", "s2", "s1"));
        enter("s2", A, network);
        List<JourneyEvent> events = tracker.assign(TRAIN, B, network, now);
        assertEquals(B, only(events, ServiceChanged.class).to());
        JourneySnapshot snapshot = tracker.snapshot(TRAIN).orElseThrow();
        assertEquals(1, snapshot.lastIndex());
        assertEquals(JourneyPhase.AT_STOP, snapshot.phase());
        // The adapter rewrites the train's tag, so later observations resolve to the new service.
        Departed departed = only(leave("s2", B, network), Departed.class);
        assertEquals(B, departed.service());
    }

    @Test
    void expiresOnlyUnloadedStaleTrains() {
        Network network = network(2);
        enter("s1", null, network);
        assertEquals(0, tracker.expire(now.plusSeconds(10), id -> true));
        assertEquals(1, tracker.expire(now.plusSeconds(10), id -> false));
        assertEquals(0, tracker.size());
    }

    @Test
    void leaveSignalsOfUnknownTrainsAreIgnored() {
        Network network = network(2, service("a/north", "s1", "s2"));
        assertTrue(leave("s1", A, network).isEmpty());
        assertEquals(0, tracker.size(), "a removed train's trailing leave events must not resurrect it");
    }

    @Test
    void relocatesTrainsWhenStopsChange() {
        Network four = network(4, service("a/north", "s1", "s2", "s3", "s4"));
        enter("s1", A, four);
        leave("s1", A, four);
        enter("s3", A, four);
        // Remove s2: s3 is now stop 2 of 3 and must not be treated as the terminus when it leaves.
        Network three = network(4, service("a/north", "s1", "s3", "s4"));
        assertTrue(tracker.relocate(three, now).isEmpty());
        assertEquals(1, tracker.snapshot(TRAIN).orElseThrow().lastIndex());
        List<JourneyEvent> events = leave("s3", A, three);
        assertEquals(2, only(events, Departed.class).nextIndex());
        assertTrue(events.stream().noneMatch(TripCompleted.class::isInstance));

        // Shorten the service so the stop it just left is the new terminus.
        Network two = network(4, service("a/north", "s1", "s3"));
        tracker.relocate(two, now);
        JourneySnapshot snapshot = tracker.snapshot(TRAIN).orElseThrow();
        assertEquals(1, snapshot.lastIndex());
        assertEquals(JourneyPhase.COMPLETED, snapshot.phase());
    }

    @Test
    void relocatingATrainAtARemovedStopKeepsBoardsWorking() {
        Network four = network(4, service("a/north", "s1", "s2", "s3", "s4"));
        enter("s4", A, four);
        Network three = network(4, service("a/north", "s1", "s2", "s3"));
        tracker.relocate(three, now);
        JourneySnapshot snapshot = tracker.snapshot(TRAIN).orElseThrow();
        assertEquals(-1, snapshot.lastIndex());
        assertEquals(JourneyPhase.PASSING, snapshot.phase());
        TravelTimes times = new TravelTimes(0.5);
        assertTrue(Departures.from("s3", platform -> true, tracker.snapshots(), three, times,
                new EtaCalculator(times, 10), now).isEmpty());
        assertTrue(leave("s4", A, three).isEmpty(), "leaving a station the service no longer lists is silent");
    }

    @Test
    void aVisitResumedWithinTheWindowAnnouncesOneDeparture() {
        Network network = network(3, service("a/north", "s1", "s2", "s3"));
        enter("s1", A, network);
        advance(5);
        only(leave("s1", A, network), Departed.class);
        advance(3);
        assertTrue(enter("s1", "2", A, network).isEmpty(), "back at another platform of the same station");
        advance(20);
        List<JourneyEvent> second = tracker.observe(StopObservation.leave(TRAIN, new PlatformRef("s1", "2"),
                trigger("s1", "2"), now), A, network);
        assertTrue(second.isEmpty(), "the departure was already announced: " + second);
        advance(30);
        Arrived arrived = only(enter("s2", A, network), Arrived.class);
        assertEquals(0, arrived.fromIndex());
        assertEquals(Duration.ofSeconds(30), arrived.travelTime(), "the segment is timed from the real departure");
    }

    @Test
    void reenteringATerminusWithinTheWindowDoesNotRepeatTheArrival() {
        Network network = network(3, service("a/north", "s1", "s2", "s3"));
        enter("s3", A, network);
        only(leave("s3", A, network), TripCompleted.class);
        advance(3);
        assertTrue(enter("s3", "2", A, network).isEmpty());
        advance(5);
        assertTrue(tracker.observe(StopObservation.leave(TRAIN, new PlatformRef("s3", "2"), trigger("s3", "2"), now),
                A, network).isEmpty());
        assertEquals(JourneyPhase.COMPLETED, tracker.snapshot(TRAIN).orElseThrow().phase());
    }
}
