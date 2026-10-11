package dev.advancedtrains.atcs.importer;

import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.operation.RouteSuggester;
import dev.advancedtrains.atcs.operation.TripLog;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole set-up flow against the reference server: stations drafted from its signs, then services
 * suggested from the trips its trains were observed making on a staging copy, named from its signs.
 */
class ReferenceNetworkTest {
    private static final StationImporter IMPORTER = new StationImporter(StationImporter.Settings.defaults());

    private static TripLog.Trip trip(String spawnSign, String visits) {
        List<PlatformRef> refs = Arrays.stream(visits.split(" ")).map(visit -> {
            String[] parts = visit.split(":");
            return new PlatformRef(parts[0], parts[1]);
        }).toList();
        return new TripLog.Trip(BlockPos.parse("world," + spawnSign), refs, true, true);
    }

    /** Trips observed on the staging copy (one of each; the test repeats them). */
    private static List<TripLog.Trip> observedTrips() {
        List<TripLog.Trip> once = List.of(
                trip("-135,72,-393", "bay-area:1 liberty:2 1st-street:1"),
                trip("-241,58,-793", "1st-street:2 liberty:1 bay-area:2"),
                trip("-406,70,-747", "metal:2 middle-of-nowhere:1 nerdy:2 falcon-heavy:2 nerdy-sky-blk-1:2 nerdy-estate-blk-2:2 nerdy-estate-blk-1:2"),
                trip("-410,70,-749", "nerdy-estate-blk-1:1 nerdy-estate-blk-2:1 nerdy-sky-blk-1:1 falcon-heavy:1 nerdy:1 middle-of-nowhere:2 metal:1"),
                trip("-210,63,-787", "main:2 new-village:2"),
                trip("-210,63,-784", "main:3 nerdy:3 golden-beach:1 new-village:3"),
                trip("-403,68,-1536", "new-village:1 golden-beach:2 nerdy:4 main:1"),
                trip("-212,63,-781", "main:4 nerdy-island:1 frostpeak:1 golden-islands:1 firework:1 overworld-terminal:1 old-village:1"),
                trip("-211,63,-780", "main:5 frostpeak:2 golden-islands:2 old-village:2"),
                trip("-821,58,815", "old-village:3 overworld-terminal:2 firework:2 golden-islands:3 frostpeak:3 nerdy-island:2 main:6"));
        List<TripLog.Trip> trips = new ArrayList<>();
        for (int run = 0; run < 3; run++) {
            trips.addAll(once);
        }
        return trips;
    }

    @Test
    void stationsThenServicesNamedFromTheServersSigns() throws IOException {
        List<ScannedSign> signs = new ArrayList<>(StationImporterTest.fixture("staging-signs.tsv"));
        signs.addAll(StationImporterTest.fixture("live-plain-signs.tsv"));
        StationImporter.Proposal stations = IMPORTER.propose(signs, Network.EMPTY);
        Network network = ProposalMerger.merge(Network.EMPTY,
                stations.stations().stream().map(StationImporter.ProposedStation::toStation).toList()).network();
        assertEquals(19, network.stations().size());

        RouteSuggester.Suggestion suggestion = RouteSuggester.suggest(observedTrips(), network, 2, platform ->
                network.station(platform.stationId())
                        .flatMap(station -> station.platform(platform.platformId()))
                        .map(found -> found.triggers().stream()
                                .flatMap(trigger -> IMPORTER.nearbyTexts(trigger, signs).stream()).toList())
                        .orElse(List.of()));

        Map<String, Line> lines = suggestion.lines().stream().collect(Collectors.toMap(Line::id, line -> line));
        assertEquals(4, lines.size(), lines::toString);
        assertEquals("Line A", lines.get("a").name());
        assertEquals("A", lines.get("a").shortName());
        assertEquals("Line 1", lines.get("1").name());
        assertEquals("Line S", lines.get("s").name());
        assertTrue(lines.containsKey("line-2") || lines.keySet().stream().anyMatch(id -> id.startsWith("line-")),
                "the Nerdy Estate line has no line-name signs and keeps a generated id");

        Service local = lines.get("a").service("to-new-village").orElseThrow();
        assertEquals("Local", local.name());
        assertEquals(4, local.size());
        Service express = lines.get("a").service("to-new-village-express").orElseThrow();
        assertEquals("Express", express.name());
        assertEquals(List.of(BlockPos.parse("world,-210,63,-787")), express.spawnSigns());
        assertEquals("Express", lines.get("1").service("to-old-village-express").orElseThrow().name());

        // Applying the suggestion leaves a consistent network.
        ProposalMerger.Result applied = ProposalMerger.merge(network, new ProposalFile.Contents(List.of(), suggestion.lines()));
        assertEquals(4, applied.linesAdded());
        assertEquals(10, applied.servicesAdded());
        assertTrue(applied.network().issues().isEmpty(), applied.network().issues()::toString);
        assertEquals(new ServiceKey("s", "to-1st-street"),
                applied.network().serviceSpawnedAt(BlockPos.parse("world,-135,72,-393")).orElseThrow());
    }

    @Test
    void tripLogSurvivesARestart() {
        TripLog log = new TripLog();
        YamlConfiguration yaml = new YamlConfiguration();
        TripLog source = new TripLog();
        for (TripLog.Trip trip : observedTrips().subList(0, 3)) {
            source.started("t", trip.spawnSign());
            for (PlatformRef visit : trip.visits()) {
                source.record(new dev.advancedtrains.atcs.operation.JourneyEvent.UnassignedArrived("t", java.time.Instant.EPOCH, visit));
            }
            source.ended("t");
        }
        assertTrue(source.dirty());
        source.save(yaml);
        log.load(yaml);
        assertEquals(source.trips(), log.trips());
    }
}
