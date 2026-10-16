package dev.advancedtrains.atcs.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkStoreHistoryTest {

    @Test
    void everyEditCanBeUndone(@TempDir Path directory) throws Exception {
        NetworkStore store = new NetworkStore(directory.resolve("network.yml"));
        store.load();
        assertThrows(NetworkStore.EditRejectedException.class, store::undo, "nothing to undo on a fresh file");
        store.edit(network -> network.toBuilder().station(Station.of("main", "Main")).build());
        store.edit(network -> network.toBuilder().station(Station.of("north", "North")).build());
        assertEquals(2, store.history().size());
        assertEquals(2, store.network().stations().size());
        store.undo();
        assertEquals(1, store.network().stations().size(), "the second edit is undone");
        assertTrue(store.network().station("main").isPresent());
        assertEquals(1, store.history().size());
        assertTrue(Files.readString(directory.resolve("network.yml")).contains("main"));
        try (var undone = Files.list(directory.resolve("history"))) {
            assertEquals(1, undone.filter(path -> path.getFileName().toString().startsWith("undone-")).count(), "the undone version is kept aside");
        }
        store.edit(network -> network.toBuilder().station(Station.of("east", "East")).build());
        assertEquals(2, store.history().size(), "editing after an undo starts from the restored version");
        store.undo();
        store.undo();
        assertEquals(0, store.network().stations().size());
        assertThrows(NetworkStore.EditRejectedException.class, store::undo);
    }

    @Test
    void historyIsBounded(@TempDir Path directory) throws Exception {
        NetworkStore store = new NetworkStore(directory.resolve("network.yml"));
        store.load();
        for (int index = 0; index < NetworkStore.HISTORY + 5; index++) {
            int number = index;
            store.edit(network -> network.toBuilder().station(Station.of("s" + number, "Station " + number)).build());
        }
        assertEquals(NetworkStore.HISTORY, store.history().size());
    }
}
