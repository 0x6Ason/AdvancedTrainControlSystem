package dev.advancedtrains.atcs.persistence;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MigrationRunner {
    private static final String INDEX_RESOURCE = "/db/migration/index.txt";
    private static final Pattern FILE_PATTERN = Pattern.compile("V(\\d+)__([A-Za-z0-9_-]+)\\.sql");

    private final Class<?> resourceOwner;
    private final Clock clock;

    public MigrationRunner(Class<?> resourceOwner, Clock clock) {
        this.resourceOwner = resourceOwner;
        this.clock = clock;
    }

    public List<Migration> loadMigrations() throws IOException {
        List<Migration> migrations = new ArrayList<>();
        try (InputStream input = requireResource(INDEX_RESOURCE);
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                String filename = line.strip();
                if (filename.isEmpty() || filename.startsWith("#")) {
                    continue;
                }
                Matcher matcher = FILE_PATTERN.matcher(filename);
                if (!matcher.matches()) {
                    throw new IOException("Invalid migration filename in index: " + filename);
                }
                migrations.add(new Migration(
                        Integer.parseInt(matcher.group(1)),
                        matcher.group(2),
                        "/db/migration/" + filename));
            }
        }
        migrations.sort((left, right) -> Integer.compare(left.version(), right.version()));
        for (int index = 1; index < migrations.size(); index++) {
            if (migrations.get(index - 1).version() == migrations.get(index).version()) {
                throw new IOException("Duplicate database migration version: " + migrations.get(index).version());
            }
        }
        return List.copyOf(migrations);
    }

    public boolean hasPendingMigrations(Connection connection) throws SQLException, IOException {
        Set<Integer> applied = readAppliedVersions(connection);
        return loadMigrations().stream().anyMatch(migration -> !applied.contains(migration.version()));
    }

    public int migrate(Connection connection) throws SQLException, IOException {
        ensureMigrationTable(connection);
        Set<Integer> applied = readAppliedVersions(connection);
        int appliedCount = 0;

        for (Migration migration : loadMigrations()) {
            if (applied.contains(migration.version())) {
                continue;
            }
            applyMigration(connection, migration);
            appliedCount++;
        }
        return appliedCount;
    }

    public int currentVersion(Connection connection) throws SQLException {
        if (!migrationTableExists(connection)) {
            return 0;
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_migration")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private void applyMigration(Connection connection, Migration migration) throws SQLException, IOException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            String script = readResource(migration.resourcePath());
            try (Statement statement = connection.createStatement()) {
                for (String sql : SqlScriptParser.splitStatements(script)) {
                    statement.execute(sql);
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO schema_migration(version, name, applied_at) VALUES (?, ?, ?)")) {
                insert.setInt(1, migration.version());
                insert.setString(2, migration.name());
                insert.setString(3, Instant.now(clock).toString());
                insert.executeUpdate();
            }
            connection.commit();
        } catch (SQLException | IOException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private Set<Integer> readAppliedVersions(Connection connection) throws SQLException {
        if (!migrationTableExists(connection)) {
            return Set.of();
        }
        Set<Integer> versions = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT version FROM schema_migration")) {
            while (result.next()) {
                versions.add(result.getInt(1));
            }
        }
        return Set.copyOf(versions);
    }

    private boolean migrationTableExists(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'schema_migration'")) {
            return query.executeQuery().next();
        }
    }

    private void ensureMigrationTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS schema_migration (
                        version INTEGER PRIMARY KEY,
                        name TEXT NOT NULL,
                        applied_at TEXT NOT NULL
                    )
                    """);
        }
    }

    private String readResource(String resourcePath) throws IOException {
        try (InputStream input = requireResource(resourcePath)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private InputStream requireResource(String resourcePath) throws IOException {
        InputStream input = resourceOwner.getResourceAsStream(resourcePath);
        if (input == null) {
            throw new IOException("Missing classpath resource: " + resourcePath);
        }
        return input;
    }
}
