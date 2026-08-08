package dev.advancedtrains.atcs.event;

import dev.advancedtrains.atcs.persistence.DatabaseConfiguration;
import dev.advancedtrains.atcs.persistence.SQLiteDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcOperationalEventStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void storesOneEventForOneIdempotencyKey() throws Exception {
        SQLiteDatabase database = new SQLiteDatabase(
                new DatabaseConfiguration(temporaryDirectory.resolve("events.db"), 1000, false),
                JdbcOperationalEventStoreTest.class,
                Clock.systemUTC());
        database.initialize();
        JdbcOperationalEventStore store = new JdbcOperationalEventStore(database, Clock.systemUTC());

        OperationalEvent first = new OperationalEvent(
                UUID.randomUUID(), "trip:1:CEN:P1:0:TRAIN_ARRIVED", OperationalEventType.TRAIN_ARRIVED,
                Instant.parse("2026-08-05T00:00:00Z"), Map.of("station_id", "CEN"));
        OperationalEvent duplicate = new OperationalEvent(
                UUID.randomUUID(), first.idempotencyKey(), first.type(), first.occurredAt(), first.attributes());

        assertTrue(store.appendIfAbsent(first));
        assertFalse(store.appendIfAbsent(duplicate));
        assertEquals(1, database.countOperationalEvents());

        try (Connection connection = database.openConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM operational_event_attribute")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }
    }
}

