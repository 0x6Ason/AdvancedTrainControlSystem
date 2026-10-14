package dev.advancedtrains.atcs.network;

import dev.advancedtrains.atcs.board.DepartureVariables;
import dev.advancedtrains.atcs.operation.Departures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static dev.advancedtrains.atcs.TestNetworks.network;
import static dev.advancedtrains.atcs.TestNetworks.service;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StationCodesTest {

    @Test
    void derivesShortCodesFromIds() {
        assertEquals("gb", StationCodes.derive("golden-beach"));
        assertEquals("1s", StationCodes.derive("1st-street"));
        assertEquals("neb1", StationCodes.derive("nerdy-estate-blk-1"));
        assertEquals("mon", StationCodes.derive("middle-of-nowhere"));
        assertEquals("main", StationCodes.derive("main"));
        assertEquals("lib", StationCodes.derive("liberty"));
        assertEquals("ysy", StationCodes.derive("ysysign"));
    }

    @Test
    void everyStationOfTheLiveNetworkGetsItsOwnCode() {
        List<String> ids = List.of("1st-street", "bay-area", "falcon-heavy", "firework", "frostpeak", "golden-beach",
                "golden-islands", "liberty", "main", "metal", "middle-of-nowhere", "nerdy-estate-blk-1",
                "nerdy-estate-blk-2", "nerdy-island", "nerdy-sky-blk-1", "nerdy", "new-village", "old-village",
                "overworld-terminal", "ysysign");
        List<Station> stations = new ArrayList<>();
        ids.forEach(id -> stations.add(Station.of(id, id)));
        List<String> problems = new ArrayList<>();
        Map<String, String> codes = StationCodes.assign(stations, problems);
        assertTrue(problems.isEmpty(), problems::toString);
        assertEquals(ids.size(), codes.values().stream().distinct().count(), codes::toString);
        assertEquals("gb", codes.get("golden-beach"));
        assertEquals("gi", codes.get("golden-islands"));
        codes.values().forEach(code -> assertTrue(code.length() <= 4, code));
    }

    @Test
    void clashingCodesAreLengthenedAndExplicitCodesWin() {
        List<String> problems = new ArrayList<>();
        Map<String, String> codes = StationCodes.assign(List.of(
                Station.of("green-bay", "Green Bay"),
                Station.of("golden-beach", "Golden Beach"),
                Station.of("metal", "Metal").withCode("gb"),
                Station.of("main", "Main").withCode("GB")), problems);
        assertEquals("gb", codes.get("main"), "an explicit code is kept (the first station by id wins a clash)");
        assertEquals("met", codes.get("metal"), "the other station asking for gb gets a code of its own");
        assertEquals(1, problems.size(), "and the clash is reported");
        assertEquals(4, codes.values().stream().distinct().count(), codes::toString);
        assertEquals("gbe", codes.get("golden-beach"));
        assertThrows(IllegalArgumentException.class, () -> Station.of("x", "X").withCode("g.b"));
        assertThrows(IllegalArgumentException.class, () -> Station.of("x", "X").withCode("toolongcode"));
    }

    @Test
    void codesAreSavedInNetworkYml() {
        Network network = Network.builder().station(Station.of("golden-beach", "Golden Beach St.").withCode("gbs")).build();
        NetworkCodec codec = new NetworkCodec();
        String yaml = codec.write(network);
        assertTrue(yaml.contains("code: gbs"), yaml);
        NetworkCodec.ReadResult read = codec.read(yaml);
        assertTrue(read.clean(), read.errors()::toString);
        assertEquals("gbs", read.network().code("golden-beach"));
        assertEquals("gbs", read.network().station("golden-beach").orElseThrow().code());
    }

    @Test
    void signVariablesFitOnASignLine() {
        Network network = network(3, service("a/north", "s1:1", "s2:1", "s3:1"), service("a/south", "s3:2", "s2:2", "s1:2"));
        Service north = network.service(ServiceKey.parse("a/north")).orElseThrow();
        Service south = network.service(ServiceKey.parse("a/south")).orElseThrow();
        List<Departures.Departure> atS2 = List.of(
                new Departures.Departure("t1", north, 1, "1", OptionalDouble.of(65), Departures.Status.EN_ROUTE),
                new Departures.Departure("t2", south, 1, "2", OptionalDouble.of(0), Departures.Status.BOARDING),
                new Departures.Departure("t3", north, 1, "1", OptionalDouble.empty(), Departures.Status.EN_ROUTE));
        Map<String, String> values = DepartureVariables.values(network, 2,
                station -> station.equals("s2") ? atS2 : List.of(), seconds -> Math.round(seconds) + "s");
        assertEquals("Station S3", values.get("s2.1d"), "first departure from any platform");
        assertEquals("65s", values.get("s2.1t"));
        assertEquals("1", values.get("s2.1p"));
        assertEquals("Boarding", values.get("s2.2t"));
        assertEquals("Station S3", values.get("s2.p1.1d"), "platform 1 lists its own trains");
        assertEquals("65s", values.get("s2.p1.1t"));
        assertEquals("--", values.get("s2.p1.2t"), "a train without an estimate");
        assertEquals("Boarding", values.get("s2.p2.1t"));
        assertEquals("", values.get("s2.p2.2t"), "nothing more from platform 2");
        assertEquals("A", values.get("s2.p2.1l"));
        values.keySet().forEach(name -> assertTrue(("%" + name + "%").length() <= 15, name));
        assertEquals("s2.p1.1t", DepartureVariables.name(network, "s2", "1", 1, DepartureVariables.TIME));
    }
}
