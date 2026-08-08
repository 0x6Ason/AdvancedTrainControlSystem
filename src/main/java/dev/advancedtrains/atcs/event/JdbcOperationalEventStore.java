package dev.advancedtrains.atcs.event;

import dev.advancedtrains.atcs.persistence.SQLiteDatabase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public final class JdbcOperationalEventStore implements OperationalEventStore {
    private final SQLiteDatabase database;
    private final Clock clock;

    public JdbcOperationalEventStore(SQLiteDatabase database, Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean appendIfAbsent(OperationalEvent event) throws SQLException {
        Objects.requireNonNull(event, "event");
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                boolean inserted = insertEvent(connection, event);
                if (inserted) {
                    insertAttributes(connection, event);
                }
                connection.commit();
                return inserted;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private boolean insertEvent(Connection connection, OperationalEvent event) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT OR IGNORE INTO operational_event(
                    id, idempotency_key, event_type, occurred_at, recorded_at
                ) VALUES (?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, event.id().toString());
            insert.setString(2, event.idempotencyKey());
            insert.setString(3, event.type().name());
            insert.setString(4, event.occurredAt().toString());
            insert.setString(5, Instant.now(clock).toString());
            return insert.executeUpdate() == 1;
        }
    }

    private void insertAttributes(Connection connection, OperationalEvent event) throws SQLException {
        if (event.attributes().isEmpty()) {
            return;
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO operational_event_attribute(event_id, attribute_key, attribute_value)
                VALUES (?, ?, ?)
                """)) {
            for (Map.Entry<String, String> attribute : event.attributes().entrySet()) {
                insert.setString(1, event.id().toString());
                insert.setString(2, attribute.getKey());
                insert.setString(3, attribute.getValue());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }
}

