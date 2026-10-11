package dev.advancedtrains.atcs.importer;

import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Platform;
import dev.advancedtrains.atcs.network.Station;
import dev.advancedtrains.atcs.importer.StationImporter.ProposedStation;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StationImporterTest {
    private final StationImporter importer = new StationImporter(StationImporter.Settings.defaults());

    static List<ScannedSign> fixture(String name) throws IOException {
        List<ScannedSign> signs = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(StationImporterTest.class.getResourceAsStream("/fixtures/" + name)),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split("\t", -1);
                signs.add(new ScannedSign(
                        new BlockPos(fields[0], Integer.parseInt(fields[1]), Integer.parseInt(fields[2]),
                                Integer.parseInt(fields[3])),
                        fields[4].equals("FRONT"),
                        List.of(fields).subList(5, Math.min(fields.length, 9))));
            }
        }
        return signs;
    }

    private static Map<String, ProposedStation> byName(StationImporter.Proposal proposal) {
        return proposal.stations().stream().collect(Collectors.toMap(ProposedStation::name, station -> station));
    }

    @Test
    void draftsTheReferenceNetworkFromItsSigns() throws IOException {
        StationImporter.Proposal proposal = importer.propose(fixture("staging-signs.tsv"), Network.EMPTY);
        Map<String, ProposedStation> stations = byName(proposal);

        assertEquals(61, proposal.stopSigns());
        assertTrue(stations.keySet().containsAll(List.of(
                "Frostpeak St.", "Golden Islands St.", "Old Village St.", "Overworld Terminal St.",
                "Nerdy Island St.", "Main St.", "New Village St.", "Golden Beach St.", "Nerdy St.",
                "Liberty St.", "1st Street St.", "Bay Area St.", "Middle of Nowhere", "Metal++",
                "Nerdy Estate BLK. 1", "Nerdy Estate BLK. 2", "Nerdy Sky BLK. 1", "Falcon Heavy")),
                stations::toString);

        ProposedStation frostpeak = stations.get("Frostpeak St.");
        assertEquals("frostpeak", frostpeak.id());
        assertEquals(3, frostpeak.platforms().size());
        assertEquals(List.of(-830, -824, -818),
                frostpeak.platforms().stream().map(platform -> platform.trigger().x()).toList(),
                "platforms are numbered along the station");
        assertEquals("1st-street", stations.get("1st Street St.").id());
        assertEquals(6, stations.get("Main St.").platforms().size());
        assertTrue(stations.get("Main St.").platforms().get(0).hints().stream()
                .anyMatch(hint -> hint.startsWith("Line ")), "platform hints show the nearby line boards");

        // Reversing sidings and stops without any announcement are left for the operator.
        assertTrue(proposal.notes().stream().anyMatch(note -> note.contains("-204 63 -787") && note.contains("reverse")),
                proposal.notes()::toString);
        assertTrue(proposal.notes().stream().anyMatch(note -> note.contains("-825 58 272")), proposal.notes()::toString);
        assertFalse(stations.containsKey("Thanks!!!"));
    }

    @Test
    void usesOrdinaryStationNameSignsWhenThereIsNoAnnouncement() throws IOException {
        List<ScannedSign> signs = new ArrayList<>(fixture("staging-signs.tsv"));
        signs.addAll(fixture("live-plain-signs.tsv"));
        Map<String, ProposedStation> stations = byName(importer.propose(signs, Network.EMPTY));
        ProposedStation firework = stations.get("Firework St.");
        assertEquals(2, firework.platforms().size(), stations::toString);
        assertEquals("firework", firework.id());
        assertFalse(stations.containsKey("Line A Firework St."));
        // Announcements outrank boards: Main St.'s Line 1 board does not split the station.
        assertEquals(6, stations.get("Main St.").platforms().size());
        assertFalse(stations.containsKey("Undrgrnd Main St."));
    }

    @Test
    void reusesExistingStationsAndSkipsBoundSigns() throws IOException {
        BlockPos boundSign = new BlockPos("world", -830, 58, -719);
        Network network = Network.builder()
                .station(new Station("frost", "Frostpeak St.",
                        Map.of("1", new Platform("1", "", List.of(boundSign))), null))
                .build();
        StationImporter.Proposal proposal = importer.propose(fixture("staging-signs.tsv"), network);
        ProposedStation frostpeak = byName(proposal).get("Frostpeak St.");
        assertTrue(frostpeak.existing());
        assertEquals("frost", frostpeak.id());
        assertEquals(List.of("2", "3"), frostpeak.platforms().stream().map(StationImporter.ProposedPlatform::id).toList());
        assertTrue(proposal.notes().get(0).startsWith("1 station sign(s) are already bound"));

        ProposalMerger.Result merged = ProposalMerger.merge(network,
                proposal.stations().stream().map(ProposedStation::toStation).toList());
        assertEquals("Frostpeak St.", merged.network().station("frost").orElseThrow().name());
        assertEquals(3, merged.network().station("frost").orElseThrow().platforms().size());
        assertTrue(merged.network().issues().isEmpty(), merged.network().issues()::toString);
    }

    @Test
    void mergeNeverRebindsATrigger() {
        BlockPos sign = new BlockPos("world", 1, 2, 3);
        Network network = Network.builder()
                .station(new Station("a", "A", Map.of("1", new Platform("1", "", List.of(sign))), null))
                .build();
        ProposalMerger.Result result = ProposalMerger.merge(network, List.of(
                new Station("b", "B", Map.of("1", new Platform("1", "", List.of(sign))), null)));
        assertEquals(0, result.stationsAdded());
        assertEquals(1, result.skipped().size());
        assertTrue(result.network().station("b").isEmpty());
    }

    @Test
    void recognisesTrainCartsHeaders() {
        assertTrue(TrainSign.isTrainHeader("[+train]"));
        assertTrue(TrainSign.isTrainHeader("[train:left]"));
        assertTrue(TrainSign.isTrainHeader(" [!cart] "));
        assertTrue(TrainSign.isTrainHeader("[+train] "));
        assertFalse(TrainSign.isTrainHeader("[trains]"));
        assertFalse(TrainSign.isTrainHeader("Line 1"));
        assertEquals("Arrived: Golden Islands St.", TrainSign.joinLines("Arrived: Golden Islands ", "St."));
        assertEquals("golden islands st", StationImporter.normalizeName("Golden  Islands St."));
    }

    @Test
    void onlyOneClusterExtendsAnExistingStationWithTheSameName() throws IOException {
        // With a small cluster radius the two Nerdy St. platform groups (about 50 blocks apart) are
        // separate clusters; only one may join the existing station.
        StationImporter.Settings defaults = StationImporter.Settings.defaults();
        StationImporter tight = new StationImporter(new StationImporter.Settings(defaults.arrivalPrefixes(),
                defaults.stationNameSuffixes(), defaults.idStripSuffixes(), 16, 6, 30));
        Network network = Network.builder()
                .station(new Station("nerdy", "Nerdy St.",
                        Map.of("1", new Platform("1", "", List.of(new BlockPos("world", -408, 70, -919)))), null))
                .build();
        StationImporter.Proposal proposal = tight.propose(fixture("staging-signs.tsv"), network);
        List<ProposedStation> nerdy = proposal.stations().stream()
                .filter(station -> station.name().equals("Nerdy St.")).toList();
        assertEquals(2, nerdy.size(), proposal.stations()::toString);
        assertEquals(1, nerdy.stream().filter(ProposedStation::existing).count());
        assertEquals(2, nerdy.stream().map(ProposedStation::id).distinct().count());
        String yaml = ProposalFile.write(proposal, null, java.time.Instant.EPOCH);
        assertEquals(2, ProposalFile.read(yaml).stations().stream()
                .filter(station -> station.name().equals("Nerdy St.")).count(), "nothing is lost in the file");
    }
}
