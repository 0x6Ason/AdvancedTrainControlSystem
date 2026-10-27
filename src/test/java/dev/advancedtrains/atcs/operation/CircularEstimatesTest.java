package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import static dev.advancedtrains.atcs.TestNetworks.network;
import static dev.advancedtrains.atcs.TestNetworks.platform;
import static dev.advancedtrains.atcs.TestNetworks.service;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CircularEstimatesTest {
    private static final Instant NOW = Instant.parse("2026-10-27T05:00:00Z");
    private static final ServiceKey KEY = ServiceKey.parse("loop/cw");
    private final Service loop = service("loop/cw", "s1", "s2", "s3", "s4").withCircular(true);
    private final Network network = network(4, loop);
    private final TravelTimes times = learned(loop, network);

    private static TravelTimes learned(Service service, Network network) {
        TravelTimes times = new TravelTimes(0.3);
        for (int from = 0; from < service.size(); from++) {
            int to = service.nextIndex(from);
            times.record(new JourneyEvent.Arrived("learn", NOW, service.key(), to,
                    platform(service.stop(to).stationId()), false, from, Duration.ofSeconds(30)), network);
        }
        return times;
    }

    private static JourneySnapshot moving(String name, int last, int elapsed) {
        Instant departure = NOW.minusSeconds(elapsed);
        return new JourneySnapshot(name, KEY, JourneyPhase.IN_TRANSIT, last, null,
                platform("s" + (last + 1)), -1, departure.minusSeconds(10), departure, departure, NOW);
    }

    private static JourneySnapshot stopped(String name, int last) {
        PlatformRef platform = platform("s" + (last + 1));
        return new JourneySnapshot(name, KEY, JourneyPhase.AT_STOP, last, platform, platform, -1, NOW, null, null, NOW);
    }

    @Test
    void healthyLoopTrainsKeepTheirOwnEtasAtEveryStop() {
        EtaCalculator plain = new EtaCalculator(times, 10);
        List<JourneySnapshot> trains = List.of(moving("A", 0, 15), moving("B", 1, 15), moving("C", 3, 15));
        ConsistentEstimates estimates = ConsistentEstimates.compute(trains, network, plain, NOW);
        for (JourneySnapshot train : trains) {
            for (int stop = 0; stop < loop.size(); stop++) {
                assertEquals(plain.secondsUntil(train, loop, stop, NOW).orElseThrow(),
                        estimates.secondsUntil(train, loop, stop, NOW).orElseThrow(), 0.001,
                        train.trainId() + " to stop " + stop + " must not wait for another train's next lap");
            }
        }
    }

    @Test
    void aBoardingTrainIsNeverMovedIntoItsNextLap() {
        JourneySnapshot boarding = stopped("boarding", 0);
        List<JourneySnapshot> trains = List.of(boarding, moving("other", 2, 15));
        EtaCalculator plain = new EtaCalculator(times, 10);
        ConsistentEstimates estimates = ConsistentEstimates.compute(trains, network, plain, NOW);
        assertEquals(0, estimates.secondsUntil(boarding, loop, 0, NOW).orElseThrow(), 0.001);
        assertEquals(40, estimates.secondsUntil(boarding, loop, 1, NOW).orElseThrow(), 0.001);
    }

    @Test
    void aQueueAcrossTheEndOfTheStopListStillWaitsForThePlatform() {
        JourneySnapshot ahead = stopped("ahead", 0);
        JourneySnapshot behind = moving("behind", 3, 28);
        EtaCalculator plain = new EtaCalculator(times, 10, context(-1, "ahead", 40));
        ConsistentEstimates estimates = ConsistentEstimates.compute(List.of(behind, ahead), network, plain, NOW);
        assertEquals(0, estimates.secondsUntil(ahead, loop, 0, NOW).orElseThrow(), 0.001);
        assertEquals(45, estimates.secondsUntil(behind, loop, 0, NOW).orElseThrow(), 0.001);
        assertEquals(85, estimates.secondsUntil(behind, loop, 1, NOW).orElseThrow(), 0.001);
        assertEquals(70, estimates.secondsUntil(ahead, loop, 1, NOW).orElseThrow(), 0.001);
    }

    @Test
    void aThreeTrainQueueCarriesEachDelayForwardOnlyOnce() {
        JourneySnapshot ahead = stopped("ahead", 0);
        JourneySnapshot middle = moving("middle", 3, 28);
        JourneySnapshot behind = moving("behind", 3, 25);
        EtaCalculator plain = new EtaCalculator(times, 10, context(-1, "ahead", 40));
        ConsistentEstimates estimates = ConsistentEstimates.compute(List.of(behind, ahead, middle), network, plain, NOW);
        for (int stop = 0; stop < loop.size(); stop++) {
            assertEquals(45 + stop * 40, estimates.secondsUntil(middle, loop, stop, NOW).orElseThrow(), 0.001);
            assertEquals(60 + stop * 40, estimates.secondsUntil(behind, loop, stop, NOW).orElseThrow(), 0.001);
        }
    }

    @Test
    void trainsOnTheSameSegmentAreOrderedByProgressNotCollectionOrder() {
        JourneySnapshot behind = moving("behind", 0, 5);
        JourneySnapshot ahead = moving("ahead", 0, 25);
        EtaCalculator plain = new EtaCalculator(times, 10);
        for (List<JourneySnapshot> trains : List.of(List.of(behind, ahead), List.of(ahead, behind))) {
            ConsistentEstimates estimates = ConsistentEstimates.compute(trains, network, plain, NOW);
            assertEquals(5, estimates.secondsUntil(ahead, loop, 1, NOW).orElseThrow(), 0.001);
            assertEquals(25, estimates.secondsUntil(behind, loop, 1, NOW).orElseThrow(), 0.001);
        }
    }

    @Test
    void recordedPositionWinsOverDepartureTimeWhenOrderingTrains() {
        JourneySnapshot behind = moving("behind", 0, 28);
        JourneySnapshot ahead = moving("ahead", 0, 10);
        EtaContext positions = new EtaContext() {
            @Override
            public OptionalDouble segmentProgress(String trainId, Service service, int fromIndex) {
                return OptionalDouble.of(trainId.equals("ahead") ? 0.9 : 0.5);
            }

            @Override
            public boolean standing(String trainId) {
                return false;
            }

            @Override
            public OptionalDouble remainingStop(String trainId, Instant now) {
                return OptionalDouble.empty();
            }
        };
        EtaCalculator plain = new EtaCalculator(times, 10, positions);
        ConsistentEstimates estimates = ConsistentEstimates.compute(List.of(behind, ahead), network, plain, NOW);
        assertEquals(20, estimates.secondsUntil(ahead, loop, 1, NOW).orElseThrow(), 0.001);
        assertEquals(35, estimates.secondsUntil(behind, loop, 1, NOW).orElseThrow(), 0.001);
    }

    @Test
    void numberingTheLoopFromADifferentStationDoesNotChangeTheEtas() {
        for (int rotation = 0; rotation < loop.size(); rotation++) {
            List<JourneySnapshot> trains = new ArrayList<>();
            for (int last : new int[] {0, 1, 3}) {
                trains.add(moving("train-" + last, (last + rotation) % loop.size(), 15));
            }
            EtaCalculator plain = new EtaCalculator(times, 10);
            ConsistentEstimates estimates = ConsistentEstimates.compute(trains, network, plain, NOW);
            for (JourneySnapshot train : trains) {
                assertEquals(15, estimates.secondsUntil(train, loop, loop.nextIndex(train.lastIndex()), NOW).orElseThrow(), 0.001);
            }
        }
    }

    @Test
    void projectionsStayBoundedOnLoopsShorterAndLongerThanTheLookahead() {
        for (int size : new int[] {2, 3, 4, 8, 13, 20}) {
            String[] stops = java.util.stream.IntStream.rangeClosed(1, size).mapToObj(i -> "s" + i).toArray(String[]::new);
            Service service = service("loop/cw", stops).withCircular(true);
            Network network = network(size, service);
            EtaCalculator plain = new EtaCalculator(learned(service, network), 10);
            List<JourneySnapshot> trains = List.of(moving("A", 0, 15), moving("B", size - 1, 15));
            ConsistentEstimates estimates = ConsistentEstimates.compute(trains, network, plain, NOW);
            for (JourneySnapshot train : trains) {
                for (int target = 0; target < size; target++) {
                    assertEquals(plain.secondsUntil(train, service, target, NOW).orElseThrow(),
                            estimates.secondsUntil(train, service, target, NOW).orElseThrow(), 0.001,
                            size + " stops: " + train.trainId() + " to " + target);
                }
            }
        }
    }

    @Test
    void aStopOutsideTheServiceDoesNotBecomeAFakeArrivalAtTheLastRouteStop() {
        JourneySnapshot passing = new JourneySnapshot("passing", KEY, JourneyPhase.PASSING, 0,
                platform("s5"), platform("s5"), -1, NOW, NOW.minusSeconds(15), NOW.minusSeconds(15), NOW);
        EtaCalculator plain = new EtaCalculator(times, 10);
        ConsistentEstimates estimates = ConsistentEstimates.compute(List.of(passing, moving("other", 2, 15)), network, plain, NOW);
        assertEquals(15, estimates.secondsUntil(passing, loop, 1, NOW).orElseThrow(), 0.001);
        assertEquals(135, estimates.secondsUntil(passing, loop, 0, NOW).orElseThrow(), 0.001);
    }

    @Test
    void aPassingTrainKeepsItsCurrentVisitSeparateFromItsNextLap() {
        JourneySnapshot passing = new JourneySnapshot("passing", KEY, JourneyPhase.PASSING, 0,
                platform("s1"), platform("s1"), -1, NOW, null, null, NOW);
        EtaCalculator plain = new EtaCalculator(times, 10);
        ConsistentEstimates estimates = ConsistentEstimates.compute(List.of(passing, moving("other", 2, 15)), network, plain, NOW);
        assertEquals(0, estimates.secondsUntil(passing, loop, 0, NOW).orElseThrow(), 0.001);
        assertEquals(30, estimates.secondsUntil(passing, loop, 1, NOW).orElseThrow(), 0.001);
    }

    @Test
    void observedFourAndEightStopLoopsMatchActualArrivalsOnBoardsAndOnboard() {
        // Positions, learned times and subsequent actual arrivals captured on 2026-10-27. The
        // live export has no departure timestamp, so the replay reconstructs it from path progress.
        assertObservedLoop(new double[] {76.77, 22.84, 17.38, 42.1}, new double[] {9.25, 9.47, 9.04, 9.02},
                List.of(new Observed(0, 0.15, -1, 65.443), new Observed(0, 0.91, -1, 7.317),
                        new Observed(3, 0, 6.07, 45.064)));
        assertObservedLoop(new double[] {17.41, 22.69, 77.67, 41.19}, new double[] {9.14, 9.12, 9.06, 9.02},
                List.of(new Observed(2, 0.36, -1, 49.675), new Observed(1, 0.07, -1, 21.12),
                        new Observed(3, 0.27, -1, 30.133)));
        assertObservedLoop(new double[] {4.79, 27.36, 0.79, 1.66, 1.89, 2.14, 3.59, 4.75},
                new double[] {5.52, 5.52, 5.49, 5.47, 5.51, 5.48, 5.43, 5.49},
                List.of(new Observed(1, 0.94, -1, 1.884), new Observed(1, 0, 4.27, 28.431),
                        new Observed(6, 0, 4.18, 4.727)));
        assertObservedLoop(new double[] {2.09, 1.9, 1.72, 0.78, 26.86, 4.58, 4.48, 3.6},
                new double[] {5.71, 5.52, 5.42, 5.51, 5.45, 6.35, 5.45, 5.56},
                List.of(new Observed(4, 0.65, -1, 9.145), new Observed(6, 0.97, -1, 0.174),
                        new Observed(2, 0, 2.18, 4.827)));
    }

    private record Observed(int last, double fraction, double stoppedFor, double actualSeconds) {
    }

    private static void assertObservedLoop(double[] segments, double[] dwells, List<Observed> observations) {
        String[] stations = java.util.stream.IntStream.rangeClosed(1, segments.length).mapToObj(i -> "s" + i).toArray(String[]::new);
        Service service = service("loop/cw", stations).withCircular(true);
        Network network = network(segments.length, service);
        TravelTimes times = new TravelTimes(0.3);
        for (int from = 0; from < segments.length; from++) {
            int to = service.nextIndex(from);
            times.record(new JourneyEvent.Arrived("learn", NOW, KEY, to, platform(stations[to]), false, from,
                    Duration.ofMillis(Math.round(segments[from] * 1000))), network);
            times.record(new JourneyEvent.Departed("learn", NOW, KEY, from, platform(stations[from]), to,
                    Duration.ofMillis(Math.round(dwells[from] * 1000))), network);
        }
        List<JourneySnapshot> trains = new ArrayList<>();
        for (int i = 0; i < observations.size(); i++) {
            Observed observed = observations.get(i);
            String name = Integer.toString(i);
            PlatformRef platform = platform(stations[observed.last()]);
            if (observed.stoppedFor() >= 0) {
                trains.add(new JourneySnapshot(name, KEY, JourneyPhase.AT_STOP, observed.last(), platform, platform, -1,
                        NOW.minusMillis(Math.round(observed.stoppedFor() * 1000)), null, null, NOW));
            } else {
                Instant departure = NOW.minusMillis(Math.round(segments[observed.last()] * observed.fraction() * 1000));
                trains.add(new JourneySnapshot(name, KEY, JourneyPhase.IN_TRANSIT, observed.last(), null, platform, -1,
                        departure.minusSeconds(10), departure, departure, NOW));
            }
        }
        EtaContext context = new EtaContext() {
            @Override
            public OptionalDouble segmentProgress(String trainId, Service service, int fromIndex) {
                return OptionalDouble.of(observations.get(Integer.parseInt(trainId)).fraction());
            }

            @Override
            public boolean standing(String trainId) {
                return false;
            }

            @Override
            public OptionalDouble remainingStop(String trainId, Instant now) {
                return OptionalDouble.empty();
            }
        };
        EtaCalculator plain = new EtaCalculator(times, 10, context);
        ConsistentEstimates estimates = ConsistentEstimates.compute(trains, network, plain, NOW);
        for (int i = 0; i < trains.size(); i++) {
            JourneySnapshot train = trains.get(i);
            Observed observed = observations.get(i);
            int target = service.nextIndex(train.lastIndex());
            double expected = observed.actualSeconds();
            double eta = estimates.secondsUntil(train, service, target, NOW).orElseThrow();
            assertEquals(expected, eta, 2, "next arrival must be seconds away, not several laps away");
            Departures.Departure board = Departures.from(stations[target], ignored -> true, trains, network, times, estimates, NOW)
                    .stream().filter(departure -> departure.trainId().equals(train.trainId())).findFirst().orElseThrow();
            assertEquals(eta, board.etaSeconds().orElseThrow(), 0.001);
            if (train.phase() == JourneyPhase.IN_TRANSIT) {
                TrainView view = TrainView.of(train, network, times, estimates, context, NOW).orElseThrow();
                assertEquals(eta, view.etaNext().orElseThrow(), 0.001);
            }
        }
    }

    private static EtaContext context(double progress, String heldTrain, double hold) {
        return new EtaContext() {
            @Override
            public OptionalDouble segmentProgress(String trainId, Service service, int fromIndex) {
                return progress < 0 ? OptionalDouble.empty() : OptionalDouble.of(progress);
            }

            @Override
            public boolean standing(String trainId) {
                return false;
            }

            @Override
            public OptionalDouble remainingStop(String trainId, Instant now) {
                return trainId.equals(heldTrain) ? OptionalDouble.of(hold) : OptionalDouble.empty();
            }
        };
    }
}
