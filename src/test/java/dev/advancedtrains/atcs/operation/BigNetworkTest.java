package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.TestNetworks;
import dev.advancedtrains.atcs.map.StationLocator;
import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.NetworkCodec;
import dev.advancedtrains.atcs.network.NetworkLint;
import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.network.Station;
import dev.advancedtrains.atcs.network.Stop;
import dev.advancedtrains.atcs.web.WebModel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A railway far bigger than any real one yet: 300 stations, 24 services of 25 stops sharing
 * stations, 150 trains running. Everything the server does every second or two for boards, the
 * website and riders has to come out well under a tick's worth of time, and the one-off jobs
 * (lint, the rail graph, the website's network file) in well under a second.
 */
class BigNetworkTest {
    private static final int STATIONS = 300;
    private static final int LINES = 12;
    private static final int STOPS = 25;
    private static final int TRAINS = 150;
    private static final Instant T0 = Instant.parse("2026-10-17T10:00:00Z");

    private static String stationAt(int line, int stop) {
        return "s" + ((line * 23 + stop * 3) % STATIONS + 1);
    }

    static Network big() {
        Network.Builder builder = Network.builder();
        for (int number = 1; number <= STATIONS; number++) {
            Station plain = TestNetworks.station("s" + number, 2);
            builder.station(new Station(plain.id(), plain.name(), plain.platforms(), TestNetworks.trigger(plain.id()), null));
        }
        for (int line = 0; line < LINES; line++) {
            List<Stop> east = new ArrayList<>();
            List<Stop> west = new ArrayList<>();
            for (int stop = 0; stop < STOPS; stop++) {
                east.add(Stop.parse(stationAt(line, stop) + ":1"));
                west.add(0, Stop.parse(stationAt(line, stop) + ":2"));
            }
            String id = "l" + line;
            builder.line(Line.of(id, "Line " + line, 0x1E88E5 + line * 0x111111)
                    .withService(Service.of(ServiceKey.parse(id + "/east"), "East", east))
                    .withService(Service.of(ServiceKey.parse(id + "/west"), "West", west)));
        }
        return builder.build();
    }

    private static TravelTimes learned(Network network) {
        TravelTimes times = new TravelTimes(0.3);
        for (Service service : network.services()) {
            for (int index = 0; index + 1 < service.size(); index++) {
                Stop next = service.stop(index + 1);
                times.record(new JourneyEvent.Arrived("x", T0, service.key(), index + 1,
                        new PlatformRef(next.stationId(), next.platformId()), index + 2 == service.size(), index,
                        Duration.ofSeconds(40 + index % 7)), network);
                times.record(new JourneyEvent.Departed("x", T0, service.key(), index,
                        new PlatformRef(service.stop(index).stationId(), service.stop(index).platformId()), index + 1,
                        Duration.ofSeconds(12)), network);
            }
        }
        return times;
    }

    private static List<JourneySnapshot> running(Network network) {
        List<Service> services = List.copyOf(network.services());
        List<JourneySnapshot> journeys = new ArrayList<>();
        for (int train = 0; train < TRAINS; train++) {
            Service service = services.get(train % services.size());
            int last = (train * 7) % (service.size() - 1);
            Stop stop = service.stop(last);
            PlatformRef platform = new PlatformRef(stop.stationId(), stop.platformId());
            Instant departed = T0.minusSeconds(5 + train % 30);
            journeys.add(new JourneySnapshot("t" + train, service.key(), JourneyPhase.IN_TRANSIT, last, null, platform, -1,
                    departed.minusSeconds(12), departed, departed, T0));
        }
        return journeys;
    }

    private static final StationLocator LOCATOR = new StationLocator() {
        @Override
        public Optional<BlockPos> station(Station station) {
            return Optional.ofNullable(station.location());
        }

        @Override
        public Optional<BlockPos> stop(Station station, Stop stop) {
            return Optional.of(TestNetworks.trigger(station.id(), stop.platformId() == null ? "1" : stop.platformId()));
        }
    };

    private static <T> T timed(String what, long budgetMillis, Supplier<T> work) {
        long start = System.nanoTime();
        T result = work.get();
        long millis = (System.nanoTime() - start) / 1_000_000;
        System.out.println("big network: " + what + " " + millis + " ms");
        assertTrue(millis <= budgetMillis, what + " took " + millis + " ms, more than " + budgetMillis);
        return result;
    }

    @Test
    void thePeriodicWorkOfASecondFitsInAFewMilliseconds() {
        Network network = big();
        assertEquals(STATIONS, network.stations().size());
        assertEquals(LINES * 2, network.services().size());
        TravelTimes times = learned(network);
        List<JourneySnapshot> journeys = running(network);
        EtaCalculator eta = new EtaCalculator(times, 10);
        // Warm up once: class loading and the JIT are not what is measured.
        ConsistentEstimates.compute(journeys, network, eta, T0);
        for (Station station : network.stations().values()) {
            Departures.from(station.id(), platform -> true, journeys, network, times, eta, T0);
        }

        ConsistentEstimates estimates = timed("consistent estimates for " + TRAINS + " trains", 150,
                () -> ConsistentEstimates.compute(journeys, network, eta, T0));
        int listed = timed("departures at every station", 250, () -> {
            int count = 0;
            for (Station station : network.stations().values()) {
                count += Departures.from(station.id(), platform -> true, journeys, network, times, estimates, T0).size();
            }
            return count;
        });
        assertTrue(listed > TRAINS * 10, "every train is listed at the stops ahead of it: " + listed);
        String live = timed("the website's live file", 300, () -> WebModel.live(network, journeys,
                journey -> TrainView.of(journey, network, times, estimates, T0),
                station -> Departures.from(station, platform -> true, journeys, network, times, estimates, T0), times, 5, T0));
        assertTrue(live.length() > 10_000);
        int views = timed("a boss bar view per train", 100, () -> {
            int count = 0;
            for (JourneySnapshot journey : journeys) {
                count += TrainView.of(journey, network, times, estimates, T0).isPresent() ? 1 : 0;
            }
            return count;
        });
        assertEquals(TRAINS, views);
    }

    @Test
    void theOneOffJobsStayUnderASecond() {
        Network network = big();
        TravelTimes times = learned(network);
        String yaml = timed("writing network.yml", 800, () -> new NetworkCodec().write(network));
        NetworkCodec.ReadResult read = timed("reading it back", 800, () -> new NetworkCodec().read(yaml));
        assertTrue(read.errors().isEmpty(), read.errors().toString());
        assertEquals(STATIONS, read.network().stations().size());
        List<NetworkLint.Finding> findings = timed("lint", 800, () -> NetworkLint.check(network, NetworkLint.Inputs.none()));
        assertTrue(findings.stream().noneMatch(finding -> finding.level() == NetworkLint.Level.PROBLEM), findings.toString());
        String site = timed("the website's network file", 800,
                () -> WebModel.network("Railway", 3, network, LOCATOR, times, 10, T0));
        assertTrue(site.contains("\"stations\""));

        TrackPaths paths = new TrackPaths();
        timed("recording every stretch of track", 1500, () -> {
            int train = 0;
            for (Service service : network.services()) {
                for (int index = 0; index + 1 < service.size(); index++) {
                    String name = "r" + train++;
                    Stop from = service.stop(index);
                    Stop to = service.stop(index + 1);
                    BlockPos a = TestNetworks.trigger(from.stationId(), from.platformId());
                    BlockPos b = TestNetworks.trigger(to.stationId(), to.platformId());
                    paths.onEvent(new JourneyEvent.Departed(name, T0, service.key(), index,
                            new PlatformRef(from.stationId(), from.platformId()), index + 1, null), network);
                    int steps = Math.max(2, Math.abs(b.x() - a.x()) / 4);
                    for (int step = 0; step <= steps; step++) {
                        double f = step / (double) steps;
                        paths.sample(name, "world", a.x() + (b.x() - a.x()) * f, 64, a.z() + (b.z() - a.z()) * f);
                    }
                    paths.onEvent(new JourneyEvent.Arrived(name, T0.plusSeconds(50), service.key(), index + 1,
                            new PlatformRef(to.stationId(), to.platformId()), index + 2 == service.size(), index,
                            Duration.ofSeconds(50)), network);
                }
            }
            return null;
        });
        RailGraph graph = timed("building the rail graph", 1500, () -> RailGraph.build(network, paths));
        assertFalse(graph.edges().isEmpty());
        Service first = network.services().iterator().next();
        List<RailGraph.Passing> passings = timed("junctions along a run", 50, () -> graph.junctionsAlong(paths.path(first, 0).orElseThrow()));
        assertTrue(passings.size() >= 0);
    }
}
