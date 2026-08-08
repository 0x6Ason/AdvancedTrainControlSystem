package dev.advancedtrains.atcs.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.Objects;

public final class SQLiteDatabase {
    private final DatabaseConfiguration configuration;
    private final MigrationRunner migrationRunner;
    private final DatabaseBackupService backupService;
    private volatile int schemaVersion;

    public SQLiteDatabase(DatabaseConfiguration configuration, Class<?> resourceOwner, Clock clock) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.migrationRunner = new MigrationRunner(resourceOwner, clock);
        this.backupService = new DatabaseBackupService(clock);
    }

    public InitializationResult initialize() throws SQLException, IOException {
        loadDriver();
        Path databasePath = configuration.databasePath();
        Path parent = databasePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        boolean existed = Files.isRegularFile(databasePath) && Files.size(databasePath) > 0;
        boolean hasPending;
        try (Connection connection = openConnection()) {
            hasPending = migrationRunner.hasPendingMigrations(connection);
            if (existed && hasPending && configuration.backupBeforeMigration()) {
                try (Statement checkpoint = connection.createStatement()) {
                    checkpoint.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                }
            }
        }

        Path backupPath = null;
        if (existed && hasPending && configuration.backupBeforeMigration()) {
            backupPath = backupService.backup(databasePath);
        }

        int applied;
        try (Connection connection = openConnection()) {
            applied = migrationRunner.migrate(connection);
            schemaVersion = migrationRunner.currentVersion(connection);
        }
        return new InitializationResult(schemaVersion, applied, backupPath);
    }

    public Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + configuration.databasePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = NORMAL");
            statement.execute("PRAGMA busy_timeout = " + configuration.busyTimeoutMillis());
        }
        return connection;
    }

    public boolean isHealthy() {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA quick_check")) {
            return result.next() && "ok".equalsIgnoreCase(result.getString(1));
        } catch (SQLException exception) {
            return false;
        }
    }

    public long countOperationalEvents() throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM operational_event")) {
            return result.next() ? result.getLong(1) : 0L;
        }
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public Path path() {
        return configuration.databasePath();
    }

    private static void loadDriver() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException exception) {
            throw new SQLException("The shaded SQLite JDBC driver is unavailable", exception);
        }
    }

    public record InitializationResult(int schemaVersion, int appliedMigrations, Path backupPath) {
    }
}
