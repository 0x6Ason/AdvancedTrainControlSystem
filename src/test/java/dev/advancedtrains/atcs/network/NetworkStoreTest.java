package dev.advancedtrains.atcs.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkStoreTest {
    @TempDir
    Path folder;

    private NetworkStore store() {
        return new NetworkStore(folder.resolve("network.yml"));
    }

    @Test
    void createsAnEmptyFileAndSavesEdits() throws Exception {
        NetworkStore store = store();
        assertTrue(store.load().isEmpty());
        assertTrue(Files.exists(store.file()));

        List<Network> seen = new ArrayList<>();
        store.addListener(seen::add);
        store.edit(network -> network.toBuilder().station(Station.of("main", "Main St.")).build());

        assertEquals(1, seen.size());
        NetworkStore reloaded = store();
        reloaded.load();
        assertEquals("Main St.", reloaded.network().station("main").orElseThrow().name());
    }

    @Test
    void refusesToOverwriteUnreloadedHandEdits() throws Exception {
        NetworkStore store = store();
        store.load();
        Files.writeString(store.file(), "stations:\n  hand:\n    name: Hand Made\n", StandardCharsets.UTF_8);

        NetworkStore.EditRejectedException rejected = assertThrows(NetworkStore.EditRejectedException.class,
                () -> store.edit(network -> network.toBuilder().station(Station.of("x", "X")).build()));
        assertTrue(rejected.getMessage().contains("reload"));
        assertTrue(Files.readString(store.file()).contains("Hand Made"), "the hand edit survives");

        store.load();
        store.edit(network -> network.toBuilder().station(Station.of("x", "X")).build());
        String saved = Files.readString(store.file());
        assertTrue(saved.contains("Hand Made") && saved.contains("name: X"));
    }

    @Test
    void aFileWithErrorsIsUsedButNeverWritten() throws Exception {
        Files.writeString(folder.resolve("network.yml"), """
                stations:
                  good:
                    name: Good
                  "bad id":
                    name: Bad
                """, StandardCharsets.UTF_8);
        NetworkStore store = store();
        List<String> errors = store.load();
        assertEquals(1, errors.size());
        assertFalse(store.writable());
        assertTrue(store.network().station("good").isPresent(), "valid parts stay usable");
        assertThrows(NetworkStore.EditRejectedException.class,
                () -> store.edit(network -> network.toBuilder().removeStation("good").build()));
        assertTrue(Files.readString(store.file()).contains("bad id"));
    }

    @Test
    void aBrokenReloadKeepsTheLastGoodNetwork() throws IOException {
        NetworkStore store = store();
        Files.writeString(store.file(), "stations:\n  a:\n    name: A\n", StandardCharsets.UTF_8);
        store.load();
        Files.writeString(store.file(), "stations: [broken\n", StandardCharsets.UTF_8);
        assertFalse(store.load().isEmpty());
        assertTrue(store.network().station("a").isPresent());
    }
}
