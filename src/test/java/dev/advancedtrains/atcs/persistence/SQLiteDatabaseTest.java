package dev.advancedtrains.atcs.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SQLiteDatabaseTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void appliesSchemaOnceAndEnablesWal() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("railcontrol.db");
        SQLiteDatabase database = createDatabase(databaseFile);

        SQLiteDatabase.InitializationResult first = database.initialize();
        SQLiteDatabase.InitializationResult second = database.initialize();

        assertEquals(7, first.appliedMigrations());
        assertEquals(0, second.appliedMigrations());
        assertEquals(7, database.schemaVersion());
        assertTrue(database.isHealthy());

        try (Connection connection = database.openConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA journal_mode")) {
            assertTrue(result.next());
            assertEquals("wal", result.getString(1).toLowerCase());
        }
    }

    private SQLiteDatabase createDatabase(Path path) {
        return new SQLiteDatabase(
                new DatabaseConfiguration(path, 1000, true),
                SQLiteDatabaseTest.class,
                Clock.systemUTC());
    }
}
