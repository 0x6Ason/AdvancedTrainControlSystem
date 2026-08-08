package dev.advancedtrains.atcs.legacy;

import dev.advancedtrains.atcs.domain.BlockPosition;
import dev.advancedtrains.atcs.persistence.DatabaseConfiguration;
import dev.advancedtrains.atcs.persistence.SQLiteDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacySignInventoryRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void inventoriesAndMarksRemovedSignsWithoutTouchingUnscannedChunks() throws Exception {
        SQLiteDatabase database = new SQLiteDatabase(
                new DatabaseConfiguration(temporaryDirectory.resolve("inventory.db"), 1000, false),
                LegacySignInventoryRepositoryTest.class,
                Clock.systemUTC());
        database.initialize();
        LegacySignInventoryRepository repository = new LegacySignInventoryRepository(database);
        LegacyTrainCartsSign sign = new LegacySignClassifier().classify(
                new BlockPosition("world", 1, 64, 1),
                "FRONT",
                List.of("[+train]", "spawn 9999", "12m", "")).orElseThrow();
        Instant firstTime = Instant.parse("2026-08-05T00:00:00Z");

        repository.storeCompletedScan(new LegacyScanResult(
                UUID.randomUUID(),
                firstTime,
                firstTime.plusSeconds(1),
                List.of(new LegacyScanChunk("world", 0, 0)),
                0,
                List.of(sign)));
        LegacyInventorySummary firstSummary = repository.loadSummary();
        assertEquals(1, firstSummary.presentSigns());
        assertEquals(1, firstSummary.signsByType().get(LegacySignType.SPAWN));

        repository.storeCompletedScan(new LegacyScanResult(
                UUID.randomUUID(),
                firstTime.plusSeconds(10),
                firstTime.plusSeconds(11),
                List.of(new LegacyScanChunk("world", 0, 0)),
                0,
                List.of()));
        assertEquals(0, repository.loadSummary().presentSigns());
    }
}

