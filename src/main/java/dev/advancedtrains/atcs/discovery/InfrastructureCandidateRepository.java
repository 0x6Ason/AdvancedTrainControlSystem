package dev.advancedtrains.atcs.discovery;

import dev.advancedtrains.atcs.config.InfrastructureDiscoveryConfiguration;
import dev.advancedtrains.atcs.domain.BlockPosition;
import dev.advancedtrains.atcs.legacy.LegacySignType;
import dev.advancedtrains.atcs.persistence.SQLiteDatabase;
import dev.advancedtrains.atcs.world.RailwayWorldPolicy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class InfrastructureCandidateRepository {
    private final SQLiteDatabase database;
    private final RailwayWorldPolicy worldPolicy;
    private final InfrastructureCandidateInference inference;
    private final Clock clock;

    public InfrastructureCandidateRepository(
            SQLiteDatabase database,
            RailwayWorldPolicy worldPolicy,
            InfrastructureDiscoveryConfiguration configuration,
            Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.worldPolicy = Objects.requireNonNull(worldPolicy, "worldPolicy");
        this.inference = new InfrastructureCandidateInference(configuration);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public InfrastructureDiscoverySummary discover() throws SQLException {
        UUID runId = UUID.randomUUID();
        Instant startedAt = Instant.now(clock);
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<UUID> sourceScanId = loadLatestSourceScan(connection);
                List<InfrastructureSourceSign> sources = loadSources(connection);
                InfrastructureCandidateSnapshot snapshot = inference.infer(
                        runId,
                        sourceScanId,
                        startedAt,
                        Instant.now(clock),
                        sources);
                storeSnapshot(connection, snapshot);
                connection.commit();
                return InfrastructureDiscoverySummary.from(snapshot);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public InfrastructureDiscoverySummary loadLatestSummary() throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT id, completed_at, source_station_signs, source_spawn_signs,
                            station_candidates, platform_candidates, departure_candidates
                           , track_control_candidates
                     FROM infrastructure_discovery_run
                     WHERE status = 'COMPLETED'
                     ORDER BY completed_at DESC
                     LIMIT 1
                     """);
             ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                return InfrastructureDiscoverySummary.empty();
            }
            return new InfrastructureDiscoverySummary(
                    Optional.of(UUID.fromString(result.getString(1))),
                    Optional.of(Instant.parse(result.getString(2))),
                    result.getInt(3),
                    result.getInt(4),
                    result.getInt(5),
                    result.getInt(6),
                    result.getInt(7),
                    result.getInt(8));
        }
    }

    private Optional<UUID> loadLatestSourceScan(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT id
                FROM legacy_scan_run
                WHERE status = 'COMPLETED'
                ORDER BY completed_at DESC
                LIMIT 1
                """);
             ResultSet result = query.executeQuery()) {
            return result.next()
                    ? Optional.of(UUID.fromString(result.getString(1)))
                    : Optional.empty();
        }
    }

    private List<InfrastructureSourceSign> loadSources(Connection connection) throws SQLException {
        Map<UUID, MutableSource> sources = new LinkedHashMap<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT inventory.id, inventory.action_type,
                       inventory.world_name, inventory.block_x, inventory.block_y, inventory.block_z,
                       attribute.attribute_key, attribute.attribute_value
                FROM legacy_sign_inventory inventory
                LEFT JOIN legacy_sign_attribute attribute ON attribute.sign_id = inventory.id
                WHERE inventory.present = 1
                ORDER BY inventory.world_name, inventory.block_x, inventory.block_y,
                         inventory.block_z, inventory.id, attribute.attribute_key
                """);
             ResultSet result = query.executeQuery()) {
            while (result.next()) {
                String worldName = result.getString(3);
                if (!worldPolicy.isActive(worldName)) {
                    continue;
                }
                UUID id = UUID.fromString(result.getString(1));
                MutableSource source = sources.computeIfAbsent(id, ignored -> new MutableSource(
                        id,
                        LegacySignType.valueOf(resultValue(result, 2)),
                        new BlockPosition(
                                worldName,
                                resultInt(result, 4),
                                resultInt(result, 5),
                                resultInt(result, 6))));
                String key = result.getString(7);
                if (key != null) {
                    source.attributes.put(key, result.getString(8));
                }
            }
        }
        return sources.values().stream().map(MutableSource::freeze).toList();
    }

    private void storeSnapshot(Connection connection, InfrastructureCandidateSnapshot snapshot)
            throws SQLException {
        insertRun(connection, snapshot);
        insertStations(connection, snapshot);
        insertPlatforms(connection, snapshot);
        insertDepartures(connection, snapshot);
        insertTrackControls(connection, snapshot);
        insertEvidence(connection, snapshot);
    }

    private void insertRun(Connection connection, InfrastructureCandidateSnapshot snapshot)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO infrastructure_discovery_run(
                    id, source_scan_id, started_at, completed_at, status,
                    source_station_signs, source_spawn_signs,
                    station_candidates, platform_candidates, departure_candidates,
                    track_control_candidates, error_message
                ) VALUES (?, ?, ?, ?, 'COMPLETED', ?, ?, ?, ?, ?, ?, NULL)
                """)) {
            insert.setString(1, snapshot.runId().toString());
            if (snapshot.sourceScanId().isPresent()) {
                insert.setString(2, snapshot.sourceScanId().orElseThrow().toString());
            } else {
                insert.setNull(2, Types.VARCHAR);
            }
            insert.setString(3, snapshot.startedAt().toString());
            insert.setString(4, snapshot.completedAt().toString());
            insert.setInt(5, snapshot.sourceStationSigns());
            insert.setInt(6, snapshot.sourceSpawnSigns());
            insert.setInt(7, snapshot.stations().size());
            insert.setInt(8, snapshot.platforms().size());
            insert.setInt(9, snapshot.departures().size());
            insert.setInt(10, snapshot.trackControls().size());
            insert.executeUpdate();
        }
    }

    private void insertStations(Connection connection, InfrastructureCandidateSnapshot snapshot)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO station_candidate(
                    id, discovery_run_id, suggested_name, normalized_name,
                    world_name, center_x, center_y, center_z, confidence, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (var station : snapshot.stations()) {
                insert.setString(1, station.id().toString());
                insert.setString(2, snapshot.runId().toString());
                setOptionalString(insert, 3, station.suggestedName());
                setOptionalString(insert, 4, station.normalizedName());
                setPosition(insert, 5, station.center());
                insert.setDouble(9, station.confidence());
                insert.setString(10, snapshot.completedAt().toString());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private void insertPlatforms(Connection connection, InfrastructureCandidateSnapshot snapshot)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO platform_candidate(
                    id, discovery_run_id, station_candidate_id, source_sign_id,
                    suggested_label, world_name, block_x, block_y, block_z,
                    confidence, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (var platform : snapshot.platforms()) {
                insert.setString(1, platform.id().toString());
                insert.setString(2, snapshot.runId().toString());
                insert.setString(3, platform.stationCandidateId().toString());
                insert.setString(4, platform.sourceSignId().toString());
                insert.setString(5, platform.suggestedLabel());
                setPosition(insert, 6, platform.position());
                insert.setDouble(10, platform.confidence());
                insert.setString(11, snapshot.completedAt().toString());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private void insertDepartures(Connection connection, InfrastructureCandidateSnapshot snapshot)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO departure_candidate(
                    id, discovery_run_id, source_sign_id, world_name, block_x, block_y, block_z,
                    direction_hint, spawn_pattern, interval_text, launch_force, always_powered,
                    confidence, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (var departure : snapshot.departures()) {
                insert.setString(1, departure.id().toString());
                insert.setString(2, snapshot.runId().toString());
                insert.setString(3, departure.sourceSignId().toString());
                setPosition(insert, 4, departure.position());
                setOptionalString(insert, 8, departure.directionHint());
                setOptionalString(insert, 9, departure.spawnPattern());
                setOptionalString(insert, 10, departure.interval());
                if (departure.launchForce().isPresent()) {
                    insert.setDouble(11, departure.launchForce().orElseThrow());
                } else {
                    insert.setNull(11, Types.REAL);
                }
                if (departure.alwaysPowered().isPresent()) {
                    insert.setInt(12, departure.alwaysPowered().orElseThrow() ? 1 : 0);
                } else {
                    insert.setNull(12, Types.INTEGER);
                }
                insert.setDouble(13, departure.confidence());
                insert.setString(14, snapshot.completedAt().toString());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private void insertEvidence(Connection connection, InfrastructureCandidateSnapshot snapshot)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT OR IGNORE INTO infrastructure_candidate_evidence(
                    discovery_run_id, candidate_type, candidate_id, evidence_kind,
                    source_sign_id, distance_blocks, evidence_text
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (var evidence : snapshot.evidence()) {
                insert.setString(1, snapshot.runId().toString());
                insert.setString(2, evidence.candidateType().name());
                insert.setString(3, evidence.candidateId().toString());
                insert.setString(4, evidence.kind().name());
                insert.setString(5, evidence.sourceSignId().toString());
                if (evidence.distanceBlocks().isPresent()) {
                    insert.setDouble(6, evidence.distanceBlocks().orElseThrow());
                } else {
                    insert.setNull(6, Types.REAL);
                }
                setOptionalString(insert, 7, evidence.evidenceText());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private void insertTrackControls(Connection connection, InfrastructureCandidateSnapshot snapshot)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO track_control_candidate(
                    id, discovery_run_id, source_sign_id,
                    world_name, block_x, block_y, block_z,
                    suggested_role, instruction_text, delay_text,
                    launch_distance_text, center_offset_text, confidence, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (var control : snapshot.trackControls()) {
                insert.setString(1, control.id().toString());
                insert.setString(2, snapshot.runId().toString());
                insert.setString(3, control.sourceSignId().toString());
                setPosition(insert, 4, control.position());
                insert.setString(8, control.suggestedRole().name());
                setOptionalString(insert, 9, control.instruction());
                setOptionalString(insert, 10, control.delay());
                setOptionalString(insert, 11, control.launchDistance());
                setOptionalString(insert, 12, control.centerOffset());
                insert.setDouble(13, control.confidence());
                insert.setString(14, snapshot.completedAt().toString());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static void setPosition(PreparedStatement statement, int firstIndex, BlockPosition position)
            throws SQLException {
        statement.setString(firstIndex, position.worldName());
        statement.setInt(firstIndex + 1, position.x());
        statement.setInt(firstIndex + 2, position.y());
        statement.setInt(firstIndex + 3, position.z());
    }

    private static void setOptionalString(
            PreparedStatement statement,
            int index,
            Optional<String> value) throws SQLException {
        if (value.isPresent()) {
            statement.setString(index, value.orElseThrow());
        } else {
            statement.setNull(index, Types.VARCHAR);
        }
    }

    private static String resultValue(ResultSet result, int index) {
        try {
            return result.getString(index);
        } catch (SQLException exception) {
            throw new SourceReadException(exception);
        }
    }

    private static int resultInt(ResultSet result, int index) {
        try {
            return result.getInt(index);
        } catch (SQLException exception) {
            throw new SourceReadException(exception);
        }
    }

    private static final class MutableSource {
        private final UUID id;
        private final LegacySignType type;
        private final BlockPosition position;
        private final Map<String, String> attributes = new LinkedHashMap<>();

        private MutableSource(UUID id, LegacySignType type, BlockPosition position) {
            this.id = id;
            this.type = type;
            this.position = position;
        }

        private InfrastructureSourceSign freeze() {
            return new InfrastructureSourceSign(id, type, position, attributes);
        }
    }

    private static final class SourceReadException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private SourceReadException(Throwable cause) {
            super(cause);
        }
    }
}
