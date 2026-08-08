package dev.advancedtrains.atcs.discovery;

import dev.advancedtrains.atcs.persistence.SQLiteDatabase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class RailCandidateRepository {
    private final SQLiteDatabase database;

    public RailCandidateRepository(SQLiteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public void store(RailCandidateSnapshot snapshot) throws SQLException {
        Objects.requireNonNull(snapshot, "snapshot");
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                insertRun(connection, snapshot);
                insertNodes(connection, snapshot);
                insertEdges(connection, snapshot);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public RailDiscoverySummary loadLatestSummary() throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT id, completed_at, discovery_mode,
                            chunks_considered, chunks_scanned, block_checks,
                            nodes_discovered, edges_discovered, unloaded_boundaries,
                            node_limit_reached
                     FROM rail_discovery_run
                     WHERE status = 'COMPLETED'
                     ORDER BY completed_at DESC
                     LIMIT 1
                     """);
             ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                return RailDiscoverySummary.empty();
            }
            return new RailDiscoverySummary(
                    Optional.of(UUID.fromString(result.getString(1))),
                    Optional.of(Instant.parse(result.getString(2))),
                    result.getString(3),
                    result.getInt(4),
                    result.getInt(5),
                    result.getLong(6),
                    result.getInt(7),
                    result.getInt(8),
                    result.getInt(9),
                    result.getInt(10) != 0);
        }
    }

    private void insertRun(Connection connection, RailCandidateSnapshot snapshot) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rail_discovery_run(
                    id, started_at, completed_at, status,
                    seeds_considered, seeds_resolved, nodes_discovered, edges_discovered,
                    unloaded_boundaries, node_limit_reached, error_message,
                    discovery_mode, chunks_considered, chunks_scanned, block_checks
                ) VALUES (?, ?, ?, 'COMPLETED', ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?)
                """)) {
            insert.setString(1, snapshot.runId().toString());
            insert.setString(2, snapshot.startedAt().toString());
            insert.setString(3, snapshot.completedAt().toString());
            insert.setInt(4, 0);
            insert.setInt(5, 0);
            insert.setInt(6, snapshot.nodes().size());
            insert.setInt(7, snapshot.edges().size());
            insert.setInt(8, snapshot.unloadedBoundaries());
            insert.setInt(9, snapshot.nodeLimitReached() ? 1 : 0);
            insert.setString(10, snapshot.discoveryMode());
            insert.setInt(11, snapshot.chunksConsidered());
            insert.setInt(12, snapshot.chunksScanned());
            insert.setLong(13, snapshot.blockChecks());
            insert.executeUpdate();
        }
    }

    private void insertNodes(Connection connection, RailCandidateSnapshot snapshot) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rail_candidate_node(
                    id, discovery_run_id, world_name, block_x, block_y, block_z,
                    material_name, rail_shape, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (RailCandidateSnapshot.Node node : snapshot.nodes()) {
                insert.setString(1, node.id().toString());
                insert.setString(2, snapshot.runId().toString());
                insert.setString(3, node.position().worldName());
                insert.setInt(4, node.position().x());
                insert.setInt(5, node.position().y());
                insert.setInt(6, node.position().z());
                insert.setString(7, node.materialName());
                insert.setString(8, node.shape().name());
                insert.setString(9, snapshot.completedAt().toString());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private void insertEdges(Connection connection, RailCandidateSnapshot snapshot) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rail_candidate_edge(
                    discovery_run_id, first_node_id, second_node_id
                ) VALUES (?, ?, ?)
                """)) {
            for (RailCandidateSnapshot.Edge edge : snapshot.edges()) {
                insert.setString(1, snapshot.runId().toString());
                insert.setString(2, edge.firstNodeId().toString());
                insert.setString(3, edge.secondNodeId().toString());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

}
