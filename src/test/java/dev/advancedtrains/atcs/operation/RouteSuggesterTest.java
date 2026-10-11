package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.network.Station;
import dev.advancedtrains.atcs.network.Stop;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteSuggesterTest {
    private static final BlockPos BAY_AREA_SPAWNER = new BlockPos("world", -135, 72, -393);
    private static final BlockPos MAIN_DEPOT = new BlockPos("world", -212, 63, -781);

    private static List<PlatformRef> visits(String... refs) {
        return Arrays.stream(refs).map(ref -> {
            String[] parts = ref.split(":");
            return new PlatformRef(parts[0], parts[1]);
        }).toList();
    }

    private static TripLog.Trip trip(BlockPos spawner, String... refs) {
        return new TripLog.Trip(spawner, visits(refs), spawner != null, true);
    }

    private static Network stations(String... ids) {
        Network.Builder builder = Network.builder();
        for (String id : ids) {
            builder.station(Station.of(id, id));
        }
        return builder.build();
    }

    private static Service only(RouteSuggester.Suggestion suggestion, String firstStation) {
        return suggestion.lines().stream().flatMap(line -> line.services().values().stream())
                .filter(service -> service.stop(0).stationId().equals(firstStation))
                .findFirst().orElseThrow(() -> new AssertionError("no service from " + firstStation + ": " + suggestion));
    }

    @Test
    void lineSTurnsAroundAtFirstStreet() {
        List<TripLog.Trip> trips = new ArrayList<>();
        for (int run = 0; run < 3; run++) {
            trips.add(trip(BAY_AREA_SPAWNER, "bay-area:1", "liberty:2", "1st-street:1", "1st-street:2", "liberty:1", "bay-area:2"));
        }
        RouteSuggester.Suggestion suggestion = RouteSuggester.suggest(trips,
                stations("bay-area", "liberty", "1st-street"), 2);

        assertEquals(1, suggestion.lines().size());
        Line line = suggestion.lines().get(0);
        assertEquals(2, line.services().size());
        Service outbound = only(suggestion, "bay-area");
        Service inbound = only(suggestion, "1st-street");
        assertEquals(List.of(new Stop("bay-area", "1", false), new Stop("liberty", "2", false), new Stop("1st-street", "1", false)),
                outbound.stops());
        assertEquals(new ServiceKey(line.id(), "to-1st-street"), outbound.key());
        assertEquals(inbound.key(), outbound.continuesAs());
        assertEquals(List.of(BAY_AREA_SPAWNER), outbound.spawnSigns());
        assertTrue(inbound.spawnSigns().isEmpty());
        assertEquals(3, suggestion.observations().get(outbound.key()));
    }

    @Test
    void reversingOnOnePlatformAlsoCutsTheTrip() {
        List<List<PlatformRef>> pieces = RouteSuggester.cut(visits(
                "bay-area:1", "liberty:2", "1st-street:1", "liberty:1", "bay-area:2"));
        assertEquals(List.of(
                visits("bay-area:1", "liberty:2", "1st-street:1"),
                visits("1st-street:1", "liberty:1", "bay-area:2")), pieces);
    }

    @Test
    void expressIsGroupedWithLocalAndNamed() {
        List<TripLog.Trip> trips = new ArrayList<>();
        for (int run = 0; run < 3; run++) {
            trips.add(trip(MAIN_DEPOT, "main:3", "nerdy:3", "golden-beach:1", "new-village:3"));
            trips.add(trip(null, "main:2", "new-village:2"));
        }
        // A train seen only for the second half of its trip counts towards the local.
        trips.add(new TripLog.Trip(null, visits("golden-beach:1", "new-village:3"), false, true));
        RouteSuggester.Suggestion suggestion = RouteSuggester.suggest(trips,
                stations("main", "nerdy", "golden-beach", "new-village"), 2);

        assertEquals(1, suggestion.lines().size());
        Service local = suggestion.lines().get(0).services().values().stream()
                .filter(service -> service.size() == 4).findFirst().orElseThrow();
        Service express = suggestion.lines().get(0).services().values().stream()
                .filter(service -> service.size() == 2).findFirst().orElseThrow();
        assertEquals("Local", local.name());
        assertEquals("Express", express.name());
        assertEquals(4, suggestion.observations().get(local.key()));
        assertTrue(!local.key().equals(express.key()), "both run to New Village but need distinct ids");
    }

    @Test
    void unrelatedRoutesBecomeSeparateLinesAndKnownOnesAreSkipped() {
        List<TripLog.Trip> trips = new ArrayList<>();
        for (int run = 0; run < 2; run++) {
            trips.add(trip(null, "bay-area:1", "liberty:2", "1st-street:1"));
            trips.add(trip(null, "main:3", "nerdy:3", "golden-beach:1"));
            trips.add(trip(null, "main:4", "nerdy-island:1", "frostpeak:1"));
        }
        Network network = stations("bay-area", "liberty", "1st-street", "main", "nerdy", "golden-beach",
                "nerdy-island", "frostpeak").toBuilder()
                .line(Line.of("1", "Line 1", 0x1E88E5).withService(Service.of(ServiceKey.parse("1/west"), "Local",
                        List.of(Stop.of("main"), Stop.of("nerdy-island"), Stop.of("frostpeak")))))
                .build();
        RouteSuggester.Suggestion suggestion = RouteSuggester.suggest(trips, network, 2);

        assertEquals(2, suggestion.lines().size(), suggestion::toString);
        assertEquals(1, suggestion.alreadyDefined());
        assertTrue(suggestion.lines().stream().noneMatch(line -> line.id().equals("1")));
    }

    @Test
    void lapsOfACircularLineBecomeOneCircularService() {
        TripLog.Trip trip = trip(null, "s1:1", "s2:1", "s3:1", "s1:1", "s2:1", "s3:1", "s1:1", "s2:1", "s3:1");
        RouteSuggester.Suggestion suggestion = RouteSuggester.suggest(List.of(trip), stations("s1", "s2", "s3"), 2);
        Service loop = only(suggestion, "s1");
        assertTrue(loop.circular());
        assertEquals(3, loop.size());
        assertNull(loop.continuesAs());
    }

    @Test
    void rarelySeenRoutesAreLeftOut() {
        RouteSuggester.Suggestion suggestion = RouteSuggester.suggest(
                List.of(trip(null, "a:1", "b:1", "c:1")), stations("a", "b", "c"), 2);
        assertTrue(suggestion.lines().isEmpty());
    }

    @Test
    void tripLogRecordsVisitsAndHandlesReusedNames() {
        TripLog log = new TripLog();
        Instant now = Instant.EPOCH;
        log.started("train1", BAY_AREA_SPAWNER);
        log.record(new JourneyEvent.UnassignedArrived("train1", now, new PlatformRef("bay-area", "1")));
        log.record(new JourneyEvent.UnassignedArrived("train1", now, new PlatformRef("bay-area", "1")));
        log.record(new JourneyEvent.UnassignedArrived("train1", now, new PlatformRef("liberty", "2")));
        // TrainCarts gave the name to a new train before ATCS saw the old one removed.
        log.started("train1", null);
        log.record(new JourneyEvent.UnassignedArrived("train1", now, new PlatformRef("main", "1")));
        log.ended("train1");

        List<TripLog.Trip> trips = log.trips();
        assertEquals(2, trips.size());
        assertEquals(visits("bay-area:1", "liberty:2"), trips.get(0).visits());
        assertEquals(BAY_AREA_SPAWNER, trips.get(0).spawnSign());
        assertTrue(!trips.get(0).ended());
        assertEquals(visits("main:1"), trips.get(1).visits());
        assertTrue(trips.get(1).ended());
    }

    @Test
    void oneOddLongTripDoesNotAbsorbAFrequentShortRoute() {
        List<TripLog.Trip> trips = new ArrayList<>();
        for (int run = 0; run < 10; run++) {
            trips.add(trip(null, "s1:1", "s2:1", "s3:1"));
        }
        trips.add(trip(null, "s1:1", "s2:1", "s3:1", "s4:1"));
        RouteSuggester.Suggestion suggestion = RouteSuggester.suggest(trips, stations("s1", "s2", "s3", "s4"), 2);
        Service frequent = only(suggestion, "s1");
        assertEquals(3, frequent.size());
        assertEquals(10, suggestion.observations().get(frequent.key()));
        assertEquals(1, suggestion.serviceCount());
    }

    @Test
    void runningTripsAreSavedToo() {
        TripLog log = new TripLog();
        log.started("train1", BAY_AREA_SPAWNER);
        assertFalse(log.dirty());
        log.record(new JourneyEvent.UnassignedArrived("train1", Instant.EPOCH, new PlatformRef("bay-area", "1")));
        log.record(new JourneyEvent.UnassignedArrived("train1", Instant.EPOCH, new PlatformRef("liberty", "2")));
        assertTrue(log.dirty());
        org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
        log.save(yaml);
        TripLog loaded = new TripLog();
        loaded.load(yaml);
        assertEquals(1, loaded.finishedTrips());
        assertEquals(visits("bay-area:1", "liberty:2"), loaded.trips().get(0).visits());
        assertFalse(loaded.trips().get(0).ended());
    }
}
