package dev.advancedtrains.atcs.network;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferTest {
    private final NetworkCodec codec = new NetworkCodec();

    @Test
    void storesTheTwoStationsInOneOrder() {
        Transfer transfer = new Transfer("Main", "1st-street", 45, true);
        assertEquals("1st-street", transfer.a());
        assertEquals("main", transfer.b());
        assertEquals(new Transfer("1st-street", "main", 45, true), transfer, "given in either order, it is the same transfer");
        assertEquals("main", transfer.other("1st-street"));
        assertTrue(transfer.involves("main"));
        assertFalse(transfer.involves("liberty"));
        assertThrows(IllegalArgumentException.class, () -> new Transfer("main", "Main", null, true));
        assertThrows(IllegalArgumentException.class, () -> new Transfer("a", "b", 0, true));
        assertThrows(IllegalArgumentException.class, () -> new Transfer("a", "b", 3601, true));
    }

    @Test
    void estimatesWalkingTimeFromDistance() {
        // 55 blocks: 55 * 1.3 / 4.3 = 16.6 s on foot, plus 10 s at the gates, rounded up to 5 s.
        assertEquals(30, Transfer.estimateSeconds(55, true));
        assertEquals(20, Transfer.estimateSeconds(55, false));
        assertEquals(5, Transfer.estimateSeconds(0, false), "never less than 5 seconds");
        assertEquals(Transfer.MAX_WALK_SECONDS, Transfer.estimateSeconds(1_000_000, true));
    }

    @Test
    void roundTripsThroughNetworkYml() {
        Network network = Network.builder()
                .station(Station.of("main", "Main St."))
                .station(Station.of("1st-street", "1st Street St."))
                .station(Station.of("liberty", "Liberty St."))
                .transfer(new Transfer("main", "1st-street", 45, true))
                .transfer(new Transfer("liberty", "main", null, false))
                .build();
        String yaml = codec.write(network);
        assertTrue(yaml.contains("between:"), yaml);
        NetworkCodec.ReadResult result = codec.read(yaml);
        assertTrue(result.clean(), () -> result.errors().toString());
        assertEquals(network.transfers(), result.network().transfers());
        assertEquals(yaml, codec.write(result.network()), "writing is stable");
        assertTrue(codec.write(Network.builder().station(Station.of("a", "A")).build()).indexOf("transfers") < 0,
                "no section when there are no transfers");
    }

    @Test
    void readsHandWrittenTransfersAndReportsMistakes() {
        String yaml = """
                stations:
                  a: { name: A }
                  b: { name: B }
                  c: { name: C }
                transfers:
                  - between: [a, b]
                    walk: "0:45"
                  - between: [b, c]
                    outside: false
                  - between: [a]
                  - between: [c, c]
                  - between: [b, a]
                  - between: [a, c]
                    outside: maybe
                  - between: [a, c]
                    walk: soon
                  - just text
                """;
        NetworkCodec.ReadResult result = codec.read(yaml);
        List<Transfer> transfers = result.network().transfers();
        assertEquals(List.of(new Transfer("a", "b", 45, true), new Transfer("b", "c", null, false)), transfers);
        assertNull(transfers.get(1).walk(), "walk left out: estimated later");
        assertEquals(6, result.errors().size(), () -> result.errors().toString());
        assertTrue(result.errors().get(0).contains("exactly two stations"), result.errors().get(0));
        assertTrue(result.errors().get(1).contains("two different stations"), result.errors().get(1));
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("outside must be true or false")));
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("walk")));
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("connected twice")));
    }

    @Test
    void networkIndexesAndChecksTransfers() {
        Network network = Network.builder()
                .station(Station.of("a", "A"))
                .station(Station.of("b", "B"))
                .transfer(Transfer.of("a", "b"))
                .transfer(new Transfer("b", "a", 60, false))
                .transfer(Transfer.of("a", "ghost"))
                .build();
        assertEquals(2, network.transfers().size(), "the second a-b transfer replaced the first");
        assertEquals(60, network.transfer("a", "b").orElseThrow().walk());
        assertEquals(2, network.transfersAt("a").size());
        assertEquals(1, network.transfersAt("b").size());
        assertTrue(network.issues().stream().anyMatch(issue -> issue.contains("unknown station 'ghost'")), network.issues()::toString);
        Network removed = network.toBuilder().removeTransfer("ghost", "a").build();
        assertEquals(1, removed.transfers().size());
        assertTrue(removed.issues().isEmpty(), removed.issues()::toString);
    }
}
