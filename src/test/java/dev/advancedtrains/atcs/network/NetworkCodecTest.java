package dev.advancedtrains.atcs.network;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkCodecTest {
    private final NetworkCodec codec = new NetworkCodec();

    @Test
    void roundTripsEverything() {
        BlockPos p1 = BlockPos.parse("world,-322,63,-802");
        BlockPos p2 = BlockPos.parse("world,-322,63,-798");
        Station main = new Station("main", "Main St.", Map.of(
                "1", new Platform("1", "Eastbound", List.of(p1)),
                "2", new Platform("2", "", List.of(p2))), BlockPos.parse("world,-320,64,-800"), null,
                Box.parse("world,-330,62,-810,-310,70,-790"));
        Station nerdy = Station.of("nerdy", "Nerdy St.").withPlatform(Platform.of("1"));
        Service north = new Service(ServiceKey.parse("a/north"), "Local",
                List.of(Stop.parse("main:1"), Stop.parse("~nerdy"), Stop.of("nerdy")), false,
                ServiceKey.parse("a/south"),
                new ServiceMatch(List.of("NewVil2Main"), List.of("a-local"), List.of("loc*")));
        Service south = new Service(ServiceKey.parse("a/south"), "Local",
                List.of(Stop.of("nerdy"), Stop.of("main")), true, ServiceKey.parse("b/x"), ServiceMatch.NONE);
        Service other = Service.of(ServiceKey.parse("b/x"), "Shuttle", List.of(Stop.of("main"), Stop.of("nerdy")));
        Network network = Network.builder()
                .station(main)
                .station(nerdy)
                .line(new Line("a", "Line A Upperground", "A+", 0xE53935, Map.of(), "Track works")
                        .withService(north).withService(south))
                .line(Line.of("b", "Line B", 0x00AA00).withService(other))
                .board(new Board("main-1", "main", List.of("1"), List.of(BlockPos.parse("world,-320,65,-802"))))
                .display(new Display("main-roadmap", Display.Kind.ROADMAP, "main", List.of("1"), ServiceKey.parse("a/north"),
                        2, 1, List.of(new Display.Frame(BlockPos.parse("world,-318,66,-802"), 0, 0, 12),
                                new Display.Frame(BlockPos.parse("world,-317,66,-802"), 1, 0, 13)), List.of(), null))
                .display(new Display("map", Display.Kind.MAP, null, List.of(), null, 1, 1, List.of(), List.of(20), null))
                .map(new MapOverrides(Map.of(
                        "main", new MapOverrides.StationOverride(3, 2, "left", false),
                        "nerdy", new MapOverrides.StationOverride(null, null, null, true)),
                        Map.of("a", 0xC62828)))
                .display(new Display("main-hologram", Display.Kind.HOLOGRAM, "main", List.of(), null, 0, 0, List.of(), List.of(),
                        new Display.Hologram("world", -318.5, 66, -800.5, java.util.UUID.fromString("6f3e2a4c-1b2d-4e5f-8a9b-0c1d2e3f4a5b"))))
                .build();

        String yaml = codec.write(network);
        NetworkCodec.ReadResult result = codec.read(yaml);

        assertTrue(result.clean(), () -> result.errors().toString());
        Network read = result.network();
        assertEquals(network.stations(), read.stations());
        assertEquals(network.lines(), read.lines());
        assertEquals(network.boards(), read.boards());
        assertEquals(network.displays(), read.displays());
        assertEquals(network.map(), read.map());
        assertTrue(yaml.contains("footprint: world,-330,62,-810,-310,70,-790"));
        assertTrue(yaml.contains("hidden: true"));
        assertTrue(yaml.contains("a: '#C62828'"));
        assertEquals(yaml, codec.write(read), "writing is stable");
        assertTrue(yaml.contains("continues-as: south"), "same-line continuation uses the short form");
        assertTrue(yaml.contains("continues-as: b/x"));
    }

    @Test
    void badMapAdjustmentsAreReportedAndSkipped() {
        String yaml = """
                stations:
                  main:
                    name: Main
                map:
                  stations:
                    main: { column: 2 }
                    other: { label: sideways }
                    nowhere: { hidden: true }
                  lines:
                    a: notacolour
                """;
        NetworkCodec.ReadResult result = codec.read(yaml);
        assertEquals(3, result.errors().size(), () -> result.errors().toString());
        assertTrue(result.errors().get(0).startsWith("map.stations.main: A pin needs both"));
        assertTrue(result.errors().get(1).startsWith("map.stations.other: Label side"));
        assertTrue(result.errors().get(2).startsWith("map.lines.a:"));
        assertTrue(result.network().map().station("nowhere").hidden(), "a valid entry is kept");
        assertTrue(result.network().issues().stream().anyMatch(issue -> issue.contains("unknown station 'nowhere'")));
    }

    @Test
    void badDisplaysAreReportedAndSkipped() {
        String yaml = """
                stations:
                  main:
                    name: Main
                displays:
                  good:
                    kind: status
                    maps: [3]
                  nokind:
                    kind: picture
                    station: main
                  noframes:
                    kind: logo
                    station: main
                  badframe:
                    kind: logo
                    station: main
                    frames:
                      - at: world,1,2,3
                """;
        NetworkCodec.ReadResult result = codec.read(yaml);
        assertEquals(List.of("good"), List.copyOf(result.network().displays().keySet()));
        assertEquals(3, result.errors().size(), () -> result.errors().toString());
        assertTrue(result.errors().get(0).startsWith("displays.nokind: Unknown display kind 'picture'"));
        assertTrue(result.errors().get(2).contains("each frame needs"));
    }

    @Test
    void acceptsCompactAndMapForms() {
        String yaml = """
                stations:
                  s1:
                    name: One
                    platforms:
                      "1": [ "world,1,2,3", "world,1,2,4" ]
                  s2:
                    name: Two
                lines:
                  a:
                    name: A
                    color: gold
                    services:
                      north:
                        stops:
                          - s1
                          - { station: s2, platform: "1", pass: true }
                        match:
                          destination: Somewhere
                """;
        NetworkCodec.ReadResult result = codec.read(yaml);
        assertTrue(result.clean(), () -> result.errors().toString());
        Network network = result.network();
        assertEquals(2, network.station("s1").orElseThrow().platform("1").orElseThrow().triggers().size());
        Service north = network.service(ServiceKey.parse("a/north")).orElseThrow();
        assertEquals("north", north.name(), "service name defaults to its id");
        assertEquals(new Stop("s2", "1", true), north.stop(1));
        assertEquals(List.of("Somewhere"), north.match().destinations());
        assertEquals(0xFFAA00, network.line("a").orElseThrow().color());
    }

    @Test
    void readsFlowListsWrittenByHand() {
        String yaml = """
                stations:
                  main:
                    name: Main St.
                    platforms:
                      '1':
                        triggers: [world,-322,63,-802, "world,-322,63,-798"]
                  nerdy: { name: Nerdy St. }
                  golden-beach: { name: Golden Beach St. }
                lines:
                  a:
                    name: Line A
                    color: '#E53935'
                    services:
                      north:
                        stops: [main:1, ~nerdy, golden-beach]
                        spawn-signs: [world,-212,63,-781]
                """;
        NetworkCodec.ReadResult result = codec.read(yaml);
        assertTrue(result.clean(), () -> result.errors().toString());
        Network network = result.network();
        assertEquals(List.of(BlockPos.parse("world,-322,63,-802"), BlockPos.parse("world,-322,63,-798")),
                network.station("main").orElseThrow().platform("1").orElseThrow().triggers());
        Service north = network.service(ServiceKey.parse("a/north")).orElseThrow();
        assertEquals(List.of(Stop.parse("main:1"), Stop.parse("~nerdy"), Stop.of("golden-beach")), north.stops());
        assertEquals(List.of(BlockPos.parse("world,-212,63,-781")), north.spawnSigns());
        assertEquals(ServiceKey.parse("a/north"), network.serviceSpawnedAt(BlockPos.parse("world,-212,63,-781")).orElseThrow());
    }

    @Test
    void reportsBadEntriesWithPathsAndSkipsThem() {
        String yaml = """
                stations:
                  good:
                    name: Good
                  "Bad Id!":
                    name: Bad
                  nameless: {}
                  located:
                    name: Located
                    platforms:
                      "1":
                        triggers: [ "world,1,2" ]
                lines:
                  a:
                    name: A
                    color: not-a-colour
                  b:
                    name: B
                    services:
                      x:
                        stops: [ "good", "bad stop!" ]
                boards:
                  board1:
                    signs: []
                """;
        NetworkCodec.ReadResult result = codec.read(yaml);
        List<String> errors = result.errors();
        assertFalse(result.clean());
        assertTrue(errors.stream().anyMatch(error -> error.startsWith("stations.Bad Id!")), errors::toString);
        assertTrue(errors.stream().anyMatch(error -> error.startsWith("stations.nameless")), errors::toString);
        assertTrue(errors.stream().anyMatch(error -> error.startsWith("stations.located.platforms.1.triggers")),
                errors::toString);
        assertTrue(errors.stream().anyMatch(error -> error.startsWith("lines.a")), errors::toString);
        assertTrue(errors.stream().anyMatch(error -> error.startsWith("lines.b.services.x")), errors::toString);
        assertTrue(errors.stream().anyMatch(error -> error.startsWith("boards.board1")), errors::toString);
        assertTrue(result.network().station("good").isPresent());
        assertTrue(result.network().line("b").orElseThrow().services().isEmpty());
    }

    @Test
    void rejectsNewerFormatsAndInvalidYaml() {
        assertFalse(codec.read("version: 99\n").clean());
        assertFalse(codec.read("stations: [unclosed\n").clean());
    }

    @Test
    void emptyDocumentIsAnEmptyNetwork() {
        NetworkCodec.ReadResult result = codec.read("");
        assertTrue(result.clean());
        assertTrue(result.network().stations().isEmpty());
        assertNull(result.network().station("x").orElse(null));
    }

    @Test
    void scalarsWhereListsAreExpectedAreErrorsNotSilentLosses() {
        String yaml = """
                stations:
                  main:
                    name: Main St.
                    platforms:
                      '1':
                        triggers: world,1,2,3
                lines:
                  a:
                    name: A
                    services:
                      north:
                        stops: main, nerdy
                boards:
                  b:
                    station: main
                    signs: world,1,2,4
                """;
        List<String> errors = codec.read(yaml).errors();
        assertEquals(3, errors.size(), errors::toString);
        assertTrue(errors.stream().anyMatch(error -> error.startsWith("stations.main.platforms.1.triggers")), errors::toString);
        assertTrue(errors.stream().anyMatch(error -> error.startsWith("lines.a.services.north")
                && error.contains("stops must be a list")), errors::toString);
        assertTrue(errors.stream().anyMatch(error -> error.startsWith("boards.b.signs")), errors::toString);
    }

    @Test
    void idsDifferingOnlyInCaseAreErrors() {
        String yaml = """
                stations:
                  Main:
                    name: Main A
                    platforms:
                      P1: {}
                      p1: {}
                  main:
                    name: Main B
                lines:
                  A:
                    name: A
                    services:
                      x: { stops: [main] }
                      X: { stops: [main] }
                  a:
                    name: a
                boards:
                  B: { station: main }
                  b: { station: main }
                """;
        List<String> errors = codec.read(yaml).errors();
        assertEquals(5, errors.stream().filter(error -> error.contains("duplicate")).count(), errors::toString);
    }
}
