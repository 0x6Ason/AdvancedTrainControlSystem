package dev.advancedtrains.atcs.legacy;

import dev.advancedtrains.atcs.persistence.SQLiteDatabase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class LegacySignInventoryRepository {
    private final SQLiteDatabase database;

    public LegacySignInventoryRepository(SQLiteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public void storeCompletedScan(LegacyScanResult scan) throws SQLException {
        Objects.requireNonNull(scan, "scan");
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                insertScanRun(connection, scan);
                insertScannedChunks(connection, scan);
                for (LegacyTrainCartsSign sign : scan.signs()) {
                    upsertSign(connection, scan, sign);
                    replaceAttributes(connection, sign);
                }
                markMissingSigns(connection, scan.runId());
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public LegacyInventorySummary loadSummary() throws SQLException {
        try (Connection connection = database.openConnection()) {
            Optional<RunSummary> lastRun = loadLastRun(connection);
            Map<LegacySignType, Integer> counts = new EnumMap<>(LegacySignType.class);
            int total = 0;
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT action_type, COUNT(*)
                    FROM legacy_sign_inventory
                    WHERE present = 1
                    GROUP BY action_type
                    ORDER BY action_type
                    """);
                 ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    LegacySignType type = LegacySignType.valueOf(result.getString(1));
                    int count = result.getInt(2);
                    counts.put(type, count);
                    total += count;
                }
            }
            int totalPresent = total;
            return lastRun
                    .map(run -> new LegacyInventorySummary(
                            Optional.of(run.id()),
                            Optional.of(run.completedAt()),
                            run.chunksScanned(),
                            run.chunksSkipped(),
                            totalPresent,
                            counts))
                    .orElseGet(LegacyInventorySummary::empty);
        }
    }

    private void insertScanRun(Connection connection, LegacyScanResult scan) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO legacy_scan_run(
                    id, started_at, completed_at, status,
                    chunks_scanned, chunks_skipped, signs_found, error_message
                ) VALUES (?, ?, ?, 'COMPLETED', ?, ?, ?, NULL)
                """)) {
            insert.setString(1, scan.runId().toString());
            insert.setString(2, scan.startedAt().toString());
            insert.setString(3, scan.completedAt().toString());
            insert.setInt(4, scan.scannedChunks().size());
            insert.setInt(5, scan.skippedChunks());
            insert.setInt(6, scan.signs().size());
            insert.executeUpdate();
        }
    }

    private void insertScannedChunks(Connection connection, LegacyScanResult scan) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO legacy_scan_chunk(scan_run_id, world_name, chunk_x, chunk_z)
                VALUES (?, ?, ?, ?)
                """)) {
            for (LegacyScanChunk chunk : scan.scannedChunks()) {
                insert.setString(1, scan.runId().toString());
                insert.setString(2, chunk.worldName());
                insert.setInt(3, chunk.chunkX());
                insert.setInt(4, chunk.chunkZ());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private void upsertSign(
            Connection connection,
            LegacyScanResult scan,
            LegacyTrainCartsSign sign) throws SQLException {
        try (PreparedStatement upsert = connection.prepareStatement("""
                INSERT INTO legacy_sign_inventory(
                    id, world_name, chunk_x, chunk_z, block_x, block_y, block_z, sign_side,
                    header_line, action_type, raw_line_1, raw_line_2, raw_line_3, raw_line_4,
                    parser_version, review_status, present, first_seen_at, last_seen_at, last_scan_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'UNREVIEWED', 1, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    world_name = excluded.world_name,
                    chunk_x = excluded.chunk_x,
                    chunk_z = excluded.chunk_z,
                    block_x = excluded.block_x,
                    block_y = excluded.block_y,
                    block_z = excluded.block_z,
                    sign_side = excluded.sign_side,
                    header_line = excluded.header_line,
                    action_type = excluded.action_type,
                    raw_line_1 = excluded.raw_line_1,
                    raw_line_2 = excluded.raw_line_2,
                    raw_line_3 = excluded.raw_line_3,
                    raw_line_4 = excluded.raw_line_4,
                    parser_version = excluded.parser_version,
                    review_status = CASE
                        WHEN legacy_sign_inventory.header_line <> excluded.header_line
                          OR legacy_sign_inventory.action_type <> excluded.action_type
                          OR legacy_sign_inventory.raw_line_1 <> excluded.raw_line_1
                          OR legacy_sign_inventory.raw_line_2 <> excluded.raw_line_2
                          OR legacy_sign_inventory.raw_line_3 <> excluded.raw_line_3
                          OR legacy_sign_inventory.raw_line_4 <> excluded.raw_line_4
                        THEN 'UNREVIEWED'
                        ELSE legacy_sign_inventory.review_status
                    END,
                    reviewed_by = CASE
                        WHEN legacy_sign_inventory.header_line <> excluded.header_line
                          OR legacy_sign_inventory.action_type <> excluded.action_type
                          OR legacy_sign_inventory.raw_line_1 <> excluded.raw_line_1
                          OR legacy_sign_inventory.raw_line_2 <> excluded.raw_line_2
                          OR legacy_sign_inventory.raw_line_3 <> excluded.raw_line_3
                          OR legacy_sign_inventory.raw_line_4 <> excluded.raw_line_4
                        THEN NULL
                        ELSE legacy_sign_inventory.reviewed_by
                    END,
                    reviewed_at = CASE
                        WHEN legacy_sign_inventory.header_line <> excluded.header_line
                          OR legacy_sign_inventory.action_type <> excluded.action_type
                          OR legacy_sign_inventory.raw_line_1 <> excluded.raw_line_1
                          OR legacy_sign_inventory.raw_line_2 <> excluded.raw_line_2
                          OR legacy_sign_inventory.raw_line_3 <> excluded.raw_line_3
                          OR legacy_sign_inventory.raw_line_4 <> excluded.raw_line_4
                        THEN NULL
                        ELSE legacy_sign_inventory.reviewed_at
                    END,
                    review_note = CASE
                        WHEN legacy_sign_inventory.header_line <> excluded.header_line
                          OR legacy_sign_inventory.action_type <> excluded.action_type
                          OR legacy_sign_inventory.raw_line_1 <> excluded.raw_line_1
                          OR legacy_sign_inventory.raw_line_2 <> excluded.raw_line_2
                          OR legacy_sign_inventory.raw_line_3 <> excluded.raw_line_3
                          OR legacy_sign_inventory.raw_line_4 <> excluded.raw_line_4
                        THEN NULL
                        ELSE legacy_sign_inventory.review_note
                    END,
                    present = 1,
                    last_seen_at = excluded.last_seen_at,
                    last_scan_id = excluded.last_scan_id
                """)) {
            upsert.setString(1, sign.id().toString());
            upsert.setString(2, sign.position().worldName());
            upsert.setInt(3, sign.chunkX());
            upsert.setInt(4, sign.chunkZ());
            upsert.setInt(5, sign.position().x());
            upsert.setInt(6, sign.position().y());
            upsert.setInt(7, sign.position().z());
            upsert.setString(8, sign.side());
            upsert.setString(9, sign.header().raw());
            upsert.setString(10, sign.type().name());
            upsert.setString(11, sign.rawLines().get(0));
            upsert.setString(12, sign.rawLines().get(1));
            upsert.setString(13, sign.rawLines().get(2));
            upsert.setString(14, sign.rawLines().get(3));
            upsert.setInt(15, sign.parserVersion());
            upsert.setString(16, scan.startedAt().toString());
            upsert.setString(17, scan.completedAt().toString());
            upsert.setString(18, scan.runId().toString());
            upsert.executeUpdate();
        }
    }

    private void replaceAttributes(Connection connection, LegacyTrainCartsSign sign) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM legacy_sign_attribute WHERE sign_id = ?")) {
            delete.setString(1, sign.id().toString());
            delete.executeUpdate();
        }
        if (sign.attributes().isEmpty()) {
            return;
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO legacy_sign_attribute(sign_id, attribute_key, attribute_value)
                VALUES (?, ?, ?)
                """)) {
            for (Map.Entry<String, String> attribute : sign.attributes().entrySet()) {
                insert.setString(1, sign.id().toString());
                insert.setString(2, attribute.getKey());
                insert.setString(3, attribute.getValue());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private void markMissingSigns(Connection connection, UUID scanRunId) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE legacy_sign_inventory
                SET present = 0
                WHERE present = 1
                  AND last_scan_id <> ?
                  AND EXISTS (
                      SELECT 1
                      FROM legacy_scan_chunk
                      WHERE scan_run_id = ?
                        AND legacy_scan_chunk.world_name = legacy_sign_inventory.world_name
                        AND legacy_scan_chunk.chunk_x = legacy_sign_inventory.chunk_x
                        AND legacy_scan_chunk.chunk_z = legacy_sign_inventory.chunk_z
                  )
                """)) {
            update.setString(1, scanRunId.toString());
            update.setString(2, scanRunId.toString());
            update.executeUpdate();
        }
    }

    private Optional<RunSummary> loadLastRun(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT id, completed_at, chunks_scanned, chunks_skipped
                FROM legacy_scan_run
                WHERE status = 'COMPLETED'
                ORDER BY completed_at DESC
                LIMIT 1
                """);
             ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                return Optional.empty();
            }
            return Optional.of(new RunSummary(
                    UUID.fromString(result.getString(1)),
                    java.time.Instant.parse(result.getString(2)),
                    result.getInt(3),
                    result.getInt(4)));
        }
    }

    private record RunSummary(UUID id, java.time.Instant completedAt, int chunksScanned, int chunksSkipped) {
    }
}
