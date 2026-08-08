package dev.advancedtrains.atcs.infrastructure.review;

import dev.advancedtrains.atcs.config.InfrastructureDiscoveryConfiguration;
import dev.advancedtrains.atcs.discovery.InfrastructureCandidateRepository;
import dev.advancedtrains.atcs.persistence.DatabaseConfiguration;
import dev.advancedtrains.atcs.persistence.SQLiteDatabase;
import dev.advancedtrains.atcs.world.RailwayWorldPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InfrastructureCandidateReviewServiceTest {
    @TempDir
    Path temporaryDirectory;

    private final MutableClock clock = new MutableClock(
            Instant.parse("2026-08-08T10:00:00Z"));
    private SQLiteDatabase database;
    private InfrastructureCandidateRepository discoveryRepository;
    private InfrastructureCandidateReviewService service;
    private MutableWorldPolicy worldPolicy;
    private UUID discoveryRunId;

    @BeforeEach
    void setUp() throws Exception {
        database = new SQLiteDatabase(
                new DatabaseConfiguration(
                        temporaryDirectory.resolve("railcontrol.db"),
                        1000,
                        false),
                InfrastructureCandidateReviewServiceTest.class,
                clock);
        database.initialize();
        storeSourceInventory();
        worldPolicy = new MutableWorldPolicy(Set.of("world"));
        discoveryRepository = new InfrastructureCandidateRepository(
                database,
                worldPolicy,
                configuration(),
                clock);
        discoveryRunId = discoveryRepository.discover().runId().orElseThrow();
        service = new InfrastructureCandidateReviewService(database, worldPolicy, clock);
    }

    @Test
    void listsLatestDepartureAndControlEvidenceWithPreservedSourceData() throws Exception {
        List<CandidateReviewItem> candidates =
                service.listLatestCandidates(Optional.empty(), Optional.empty());

        assertEquals(3, candidates.size());
        assertEquals(1, candidates.stream()
                .filter(candidate -> candidate.kind() == CandidateKind.DEPARTURE)
                .count());
        assertEquals(2, candidates.stream()
                .filter(candidate -> candidate.kind() == CandidateKind.TRACK_CONTROL)
                .count());

        CandidateReviewItem departure = candidate(CandidateKind.DEPARTURE, "spawnPattern", "12m");
        assertEquals(discoveryRunId, departure.discoveryRunId());
        assertEquals("world", departure.worldName());
        assertEquals(List.of("[+train]", "spawn", "12m", "0.4"), departure.rawLines());
        assertEquals("12m", departure.attributes().get("spawn_pattern"));
        assertEquals(0.85, departure.confidence());
        assertEquals(CandidateAuthorityRole.DEPARTURE_POINT,
                departure.suggestedRole().orElseThrow());
        assertEquals("sha256-v1:", departure.evidenceFingerprint().substring(0, 10));
        assertEquals(1, departure.evidenceRevision());
        assertEquals(1, departure.evidence().size());
        assertEquals(CandidateDecisionStatus.PENDING, departure.decision().status());
        assertEquals(0, departure.decision().version());

        CandidateReviewItem continueControl =
                candidate(CandidateKind.TRACK_CONTROL, "instruction", "continue");
        assertEquals(CandidateAuthorityRole.MOTION_CONTROL,
                continueControl.suggestedRole().orElseThrow());
        CandidateReviewItem reverseControl =
                candidate(CandidateKind.TRACK_CONTROL, "instruction", "reverse");
        assertEquals(CandidateAuthorityRole.REVERSING_CONTROL,
                reverseControl.suggestedRole().orElseThrow());

        assertEquals(2, service.listLatestCandidates(
                Optional.of(CandidateKind.TRACK_CONTROL),
                Optional.of(CandidateDecisionStatus.PENDING)).size());
        assertTrue(service.findLatestCandidate(departure.candidateId()).isPresent());
    }

    @Test
    void includeRequiresExplicitIdentityAndAtomicallyCreatesAuthorityAndAudit() throws Exception {
        CandidateReviewItem departure = candidate(CandidateKind.DEPARTURE, "spawnPattern", "12m");

        assertThrows(CandidateAdoptionException.class, () -> service.decide(
                departure.candidateId(),
                includeCommand(
                        departure,
                        new CandidateAuthorityInput(Optional.empty(), Optional.of("Depot starter")))));
        assertThrows(CandidateAdoptionException.class, () -> service.decide(
                departure.candidateId(),
                includeCommand(
                        departure,
                        new CandidateAuthorityInput(Optional.of("DEPOT_START"), Optional.empty()))));

        CandidateDecisionResult result = service.decide(
                departure.candidateId(),
                includeCommand(
                        departure,
                        new CandidateAuthorityInput(
                                Optional.of("DEPOT_START"),
                                Optional.of("Depot starter"))));

        assertTrue(result.authorityChanged());
        assertEquals(CandidateDecisionStatus.INCLUDED, result.candidate().decision().status());
        assertEquals(CandidateAuthorityRole.DEPARTURE_POINT,
                result.candidate().decision().authorityRole().orElseThrow());
        assertEquals("DEPOT_START", result.candidate().decision().targetId().orElseThrow());
        assertEquals("Depot starter",
                result.candidate().decision().authorityDisplayName().orElseThrow());
        assertEquals(1, result.candidate().decision().version());

        try (Connection connection = database.openConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT display_name, enabled, source_discovery_run_id,
                            source_candidate_id, source_sign_id,
                            source_evidence_fingerprint, source_evidence_revision
                     FROM departure_point
                     WHERE id = 'DEPOT_START'
                     """);
             ResultSet authority = query.executeQuery()) {
            assertTrue(authority.next());
            assertEquals("Depot starter", authority.getString(1));
            assertEquals(1, authority.getInt(2));
            assertEquals(discoveryRunId.toString(), authority.getString(3));
            assertEquals(departure.candidateId().toString(), authority.getString(4));
            assertEquals(departure.sourceSignId().toString(), authority.getString(5));
            assertEquals(departure.evidenceFingerprint(), authority.getString(6));
            assertEquals(1, authority.getInt(7));
        }
        assertEquals(1, count("infrastructure_authority_identity"));
        assertEquals(1, count("audit_record"));
    }

    @Test
    void clearReturnsToPendingWithMonotonicVersionAndDisablesAuthority() throws Exception {
        CandidateReviewItem departure = candidate(CandidateKind.DEPARTURE, "spawnPattern", "12m");
        CandidateDecisionResult included = service.decide(
                departure.candidateId(),
                includeCommand(
                        departure,
                        new CandidateAuthorityInput(
                                Optional.of("DEPOT_START"),
                                Optional.of("Depot starter"))));

        CandidateDecisionResult cleared = service.decide(
                departure.candidateId(),
                new CandidateDecisionCommand(
                        discoveryRunId,
                        CandidateDecisionAction.CLEAR,
                        included.candidate().decision().version(),
                        Optional.of("This note must not survive clear"),
                        Optional.empty(),
                        "operator"));

        assertTrue(cleared.authorityChanged());
        assertEquals(CandidateDecisionStatus.PENDING, cleared.candidate().decision().status());
        assertEquals(2, cleared.candidate().decision().version());
        assertTrue(cleared.candidate().decision().targetId().isEmpty());
        assertTrue(cleared.candidate().decision().authorityRole().isEmpty());
        assertTrue(cleared.candidate().decision().note().isEmpty());
        assertEquals(0, scalarInt("SELECT enabled FROM departure_point WHERE id = 'DEPOT_START'"));
        assertEquals(0, count("infrastructure_authority_identity"));
        assertEquals(2, count("audit_record"));
    }

    @Test
    void changedEvidenceMarksDecisionStaleDisablesAuthorityAndBlocksReadoption() throws Exception {
        CandidateReviewItem control =
                candidate(CandidateKind.TRACK_CONTROL, "instruction", "continue");
        CandidateDecisionResult included = service.decide(
                control.candidateId(),
                includeCommand(
                        control,
                        new CandidateAuthorityInput(
                                Optional.of("BOOSTER_EAST"),
                                Optional.of("East booster"))));
        changeRawSignLine(control.sourceSignId(), "changed after discovery");

        assertEquals(1, service.invalidateStaleDecisions());
        CandidateReviewItem stale = service.findLatestCandidate(control.candidateId()).orElseThrow();
        assertEquals(CandidateDecisionStatus.STALE, stale.decision().status());
        assertEquals(2, stale.decision().version());
        assertTrue(stale.decision().targetId().isEmpty());
        assertTrue(stale.decision().authorityDisplayName().isEmpty());
        assertEquals(0, scalarInt(
                "SELECT enabled FROM track_control_point WHERE id = 'BOOSTER_EAST'"));
        assertEquals(0, count("infrastructure_authority_identity"));
        assertThrows(CandidateConflictException.class, () -> service.decide(
                control.candidateId(),
                new CandidateDecisionCommand(
                        discoveryRunId,
                        CandidateDecisionAction.INCLUDE,
                        stale.decision().version(),
                        Optional.empty(),
                        Optional.of(new CandidateAuthorityInput(
                                Optional.of("BOOSTER_EAST"),
                                Optional.of("East booster"))),
                        "operator")));
        assertEquals(2, included.candidate().decision().version() + 1);
    }

    @Test
    void changedUndecidedEvidenceIsExposedAsStaleAndRejectsEveryAction() throws Exception {
        CandidateReviewItem control =
                candidate(CandidateKind.TRACK_CONTROL, "instruction", "reverse");
        changeRawSignLine(control.sourceSignId(), "changed before review");

        CandidateReviewItem stale = service.findLatestCandidate(control.candidateId()).orElseThrow();

        assertEquals(CandidateDecisionStatus.STALE, stale.decision().status());
        assertEquals(0, stale.decision().version());
        assertTrue(stale.decision().staleReason().orElseThrow().contains("changed"));
        for (CandidateDecisionAction action : List.of(
                CandidateDecisionAction.IGNORE,
                CandidateDecisionAction.CLEAR)) {
            assertThrows(CandidateConflictException.class, () -> service.decide(
                    control.candidateId(),
                    new CandidateDecisionCommand(
                            discoveryRunId,
                            action,
                            0,
                            Optional.empty(),
                            Optional.empty(),
                            "operator")));
        }
        assertEquals(0, count("infrastructure_candidate_decision"));
        assertEquals(0, count("audit_record"));
    }

    @Test
    void concurrentIncludesCommitExactlyOneVersionAndOneAudit() throws Exception {
        CandidateReviewItem departure = candidate(CandidateKind.DEPARTURE, "spawnPattern", "12m");
        CandidateDecisionCommand command = includeCommand(
                departure,
                new CandidateAuthorityInput(
                        Optional.of("DEPOT_START"),
                        Optional.of("Depot starter")));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<String> request = () -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try {
                    service.decide(departure.candidateId(), command);
                    return "SUCCESS";
                } catch (CandidateConflictException exception) {
                    return "CONFLICT";
                }
            };
            Future<String> first = executor.submit(request);
            Future<String> second = executor.submit(request);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            assertEquals(
                    Set.of("SUCCESS", "CONFLICT"),
                    Set.copyOf(List.of(
                            first.get(5, TimeUnit.SECONDS),
                            second.get(5, TimeUnit.SECONDS))));
        } finally {
            executor.shutdownNow();
        }

        CandidateReviewItem committed = service.findLatestCandidate(departure.candidateId()).orElseThrow();
        assertEquals(CandidateDecisionStatus.INCLUDED, committed.decision().status());
        assertEquals(1, committed.decision().version());
        assertEquals(1, count("departure_point"));
        assertEquals(1, scalarInt("SELECT enabled FROM departure_point WHERE id = 'DEPOT_START'"));
        assertEquals(1, count("audit_record"));
    }

    @Test
    void inactiveWorldInvalidatesAuthorityAndHidesCandidateAccess() throws Exception {
        CandidateReviewItem departure = candidate(CandidateKind.DEPARTURE, "spawnPattern", "12m");
        service.decide(
                departure.candidateId(),
                includeCommand(
                        departure,
                        new CandidateAuthorityInput(
                                Optional.of("DEPOT_START"),
                                Optional.of("Depot starter"))));

        worldPolicy.replace(Set.of());

        assertEquals(1, service.invalidateStaleDecisions());
        assertEquals(0, scalarInt("SELECT enabled FROM departure_point WHERE id = 'DEPOT_START'"));
        assertEquals(0, count("infrastructure_authority_identity"));
        assertTrue(service.listLatestCandidates(Optional.empty(), Optional.empty()).isEmpty());
        assertTrue(service.findLatestCandidate(departure.candidateId()).isEmpty());
        assertThrows(CandidateNotFoundException.class, () -> service.decide(
                departure.candidateId(),
                new CandidateDecisionCommand(
                        discoveryRunId,
                        CandidateDecisionAction.IGNORE,
                        2,
                        Optional.empty(),
                        Optional.empty(),
                        "operator")));
    }

    @Test
    void authorityLocationCollisionIsAnAdoptionConflictWithoutPartialWrites() throws Exception {
        CandidateReviewItem control =
                candidate(CandidateKind.TRACK_CONTROL, "instruction", "continue");
        insertManualDepartureAt(control);

        assertThrows(CandidateAdoptionException.class, () -> service.decide(
                control.candidateId(),
                includeCommand(
                        control,
                        new CandidateAuthorityInput(
                                Optional.of("BOOSTER_EAST"),
                                Optional.of("East booster")))));

        assertEquals(0, count("track_control_point"));
        assertEquals(0, count("infrastructure_candidate_decision"));
        assertEquals(0, count("audit_record"));
    }

    @Test
    void schemaRejectsMixedEvidenceAndPartialAuthorityBindings() throws Exception {
        CandidateReviewItem departure = candidate(CandidateKind.DEPARTURE, "spawnPattern", "12m");
        try (Connection connection = database.openConnection()) {
            assertThrows(java.sql.SQLException.class, () -> {
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO infrastructure_candidate_decision(
                            discovery_run_id, candidate_type, candidate_id, source_sign_id,
                            evidence_fingerprint, evidence_revision, decision_status,
                            version, decided_by, decided_at
                        ) VALUES (?, 'DEPARTURE', ?, ?, 'sha256-v1:not-the-evidence', 1,
                                  'IGNORED', 1, 'operator', ?)
                        """)) {
                    insert.setString(1, discoveryRunId.toString());
                    insert.setString(2, departure.candidateId().toString());
                    insert.setString(3, departure.sourceSignId().toString());
                    insert.setString(4, clock.instant().toString());
                    insert.executeUpdate();
                }
            });
            assertThrows(java.sql.SQLException.class, () -> {
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO infrastructure_candidate_decision(
                            discovery_run_id, candidate_type, candidate_id, source_sign_id,
                            evidence_fingerprint, evidence_revision, decision_status,
                            authority_role, target_id, version, decided_by, decided_at
                        ) VALUES (?, 'DEPARTURE', ?, ?, ?, 1, 'INCLUDED',
                                  'DEPARTURE_POINT', 'MISSING_AUTHORITY', 1, 'operator', ?)
                        """)) {
                    insert.setString(1, discoveryRunId.toString());
                    insert.setString(2, departure.candidateId().toString());
                    insert.setString(3, departure.sourceSignId().toString());
                    insert.setString(4, departure.evidenceFingerprint());
                    insert.setString(5, clock.instant().toString());
                    insert.executeUpdate();
                }
            });
            assertThrows(java.sql.SQLException.class, () -> {
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO infrastructure_candidate_evidence(
                            discovery_run_id, candidate_type, candidate_id, evidence_kind,
                            source_sign_id, evidence_text
                        ) VALUES (?, 'DEPARTURE', ?, 'SPAWN_SIGN', ?, 'orphan evidence')
                        """)) {
                    insert.setString(1, discoveryRunId.toString());
                    insert.setString(2, UUID.randomUUID().toString());
                    insert.setString(3, departure.sourceSignId().toString());
                    insert.executeUpdate();
                }
            });
            assertThrows(java.sql.SQLException.class, () -> {
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO departure_point(
                            id, display_name, world_name, block_x, block_y, block_z,
                            source_discovery_run_id, enabled, version, created_at, updated_at
                        ) VALUES ('PARTIAL', 'Partial', 'world', 500, 64, 500,
                                  ?, 1, 1, ?, ?)
                        """)) {
                    insert.setString(1, discoveryRunId.toString());
                    insert.setString(2, clock.instant().toString());
                    insert.setString(3, clock.instant().toString());
                    insert.executeUpdate();
                }
            });
            insertStationCandidate(connection);
            insertPlatformCandidate(connection, "PLATFORM_ONE", departure.sourceSignId());
            assertThrows(java.sql.SQLException.class, () ->
                    insertPlatformCandidate(connection, "PLATFORM_TWO", departure.sourceSignId()));
        }
    }

    @Test
    void newerRunInvalidatesOldDecisionAndOnlyItsCandidatesAreListed() throws Exception {
        CandidateReviewItem departure = candidate(CandidateKind.DEPARTURE, "spawnPattern", "12m");
        service.decide(
                departure.candidateId(),
                includeCommand(
                        departure,
                        new CandidateAuthorityInput(
                                Optional.of("DEPOT_START"),
                                Optional.of("Depot starter"))));
        clock.advanceSeconds(60);
        UUID newerRunId = discoveryRepository.discover().runId().orElseThrow();

        assertEquals(1, service.invalidateStaleDecisions());
        assertEquals(0, scalarInt("SELECT enabled FROM departure_point WHERE id = 'DEPOT_START'"));
        assertEquals(0, count("infrastructure_authority_identity"));

        List<CandidateReviewItem> latest =
                service.listLatestCandidates(Optional.empty(), Optional.empty());

        assertEquals(3, latest.size());
        assertTrue(latest.stream().allMatch(candidate -> candidate.discoveryRunId().equals(newerRunId)));
        assertTrue(latest.stream().allMatch(
                candidate -> candidate.decision().status() == CandidateDecisionStatus.PENDING));
        assertFalse(service.findLatestCandidate(departure.candidateId()).isPresent());
        assertEquals("STALE", scalarString("""
                SELECT decision_status
                FROM infrastructure_candidate_decision
                WHERE discovery_run_id = '""" + discoveryRunId + "'"));
        assertEquals(0, scalarInt("SELECT enabled FROM departure_point WHERE id = 'DEPOT_START'"));

        CandidateReviewItem replacement = latest.stream()
                .filter(candidate -> candidate.kind() == CandidateKind.DEPARTURE)
                .findFirst()
                .orElseThrow();
        service.decide(
                replacement.candidateId(),
                includeCommand(
                        replacement,
                        new CandidateAuthorityInput(
                                Optional.of("DEPOT_START"),
                                Optional.of("Depot starter"))));
        service.listLatestCandidates(Optional.empty(), Optional.empty());
        assertEquals(1, scalarInt("SELECT enabled FROM departure_point WHERE id = 'DEPOT_START'"));
    }

    private CandidateReviewItem candidate(
            CandidateKind kind,
            String property,
            String value) throws Exception {
        return service.listLatestCandidates(Optional.of(kind), Optional.empty()).stream()
                .filter(item -> value.equals(item.candidateProperties().get(property)))
                .findFirst()
                .orElseThrow();
    }

    private CandidateDecisionCommand includeCommand(
            CandidateReviewItem candidate,
            CandidateAuthorityInput authority) {
        return new CandidateDecisionCommand(
                candidate.discoveryRunId(),
                CandidateDecisionAction.INCLUDE,
                candidate.decision().version(),
                Optional.of("Reviewed against sign evidence"),
                Optional.of(authority),
                "operator");
    }

    private void storeSourceInventory() throws Exception {
        UUID scanId = UUID.randomUUID();
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement run = connection.prepareStatement("""
                    INSERT INTO legacy_scan_run(
                        id, started_at, completed_at, status,
                        chunks_scanned, chunks_skipped, signs_found, error_message
                    ) VALUES (?, ?, ?, 'COMPLETED', 1, 0, 3, NULL)
                    """)) {
                run.setString(1, scanId.toString());
                run.setString(2, clock.instant().minusSeconds(1).toString());
                run.setString(3, clock.instant().toString());
                run.executeUpdate();
            }
            insertSign(
                    connection,
                    scanId,
                    UUID.randomUUID(),
                    "SPAWN",
                    10,
                    List.of("[+train]", "spawn", "12m", "0.4"),
                    Map.of(
                            "header_options", ":north",
                            "spawn_pattern", "12m",
                            "interval", "30s",
                            "launch_force", "0.4",
                            "always_powered", "true"));
            insertSign(
                    connection,
                    scanId,
                    UUID.randomUUID(),
                    "STATION",
                    20,
                    List.of("[train]", "station", "continue", "2"),
                    Map.of("instruction", "continue", "delay", "2"));
            insertSign(
                    connection,
                    scanId,
                    UUID.randomUUID(),
                    "STATION",
                    30,
                    List.of("[train]", "station", "reverse", "0"),
                    Map.of("instruction", "reverse", "delay", "0"));
            connection.commit();
        }
    }

    private void insertSign(
            Connection connection,
            UUID scanId,
            UUID signId,
            String actionType,
            int blockX,
            List<String> rawLines,
            Map<String, String> attributes) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO legacy_sign_inventory(
                    id, world_name, chunk_x, chunk_z,
                    block_x, block_y, block_z, sign_side,
                    header_line, action_type,
                    raw_line_1, raw_line_2, raw_line_3, raw_line_4,
                    parser_version, present, first_seen_at, last_seen_at, last_scan_id
                ) VALUES (?, 'world', 0, 0, ?, 64, 0, 'FRONT', ?, ?, ?, ?, ?, ?, 1, 1, ?, ?, ?)
                """)) {
            insert.setString(1, signId.toString());
            insert.setInt(2, blockX);
            insert.setString(3, rawLines.get(0));
            insert.setString(4, actionType);
            insert.setString(5, rawLines.get(0));
            insert.setString(6, rawLines.get(1));
            insert.setString(7, rawLines.get(2));
            insert.setString(8, rawLines.get(3));
            insert.setString(9, clock.instant().toString());
            insert.setString(10, clock.instant().toString());
            insert.setString(11, scanId.toString());
            insert.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO legacy_sign_attribute(sign_id, attribute_key, attribute_value)
                VALUES (?, ?, ?)
                """)) {
            for (Map.Entry<String, String> attribute : attributes.entrySet()) {
                insert.setString(1, signId.toString());
                insert.setString(2, attribute.getKey());
                insert.setString(3, attribute.getValue());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private void changeRawSignLine(UUID signId, String value) throws Exception {
        try (Connection connection = database.openConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE legacy_sign_inventory
                     SET raw_line_3 = ?
                     WHERE id = ?
                     """)) {
            update.setString(1, value);
            update.setString(2, signId.toString());
            update.executeUpdate();
        }
    }

    private void insertManualDepartureAt(CandidateReviewItem candidate) throws Exception {
        try (Connection connection = database.openConnection();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO departure_point(
                         id, display_name, world_name, block_x, block_y, block_z,
                         enabled, version, created_at, updated_at
                     ) VALUES ('MANUAL_POINT', 'Manual point', ?, ?, ?, ?, 1, 1, ?, ?)
                     """)) {
            insert.setString(1, candidate.worldName());
            insert.setInt(2, candidate.blockX());
            insert.setInt(3, candidate.blockY());
            insert.setInt(4, candidate.blockZ());
            insert.setString(5, clock.instant().toString());
            insert.setString(6, clock.instant().toString());
            insert.executeUpdate();
        }
    }

    private void insertStationCandidate(Connection connection) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO station_candidate(
                    id, discovery_run_id, suggested_name, normalized_name,
                    world_name, center_x, center_y, center_z, confidence, created_at
                ) VALUES ('STATION_TEST', ?, 'Test station', 'test station',
                          'world', 0, 64, 0, 0.5, ?)
                """)) {
            insert.setString(1, discoveryRunId.toString());
            insert.setString(2, clock.instant().toString());
            insert.executeUpdate();
        }
    }

    private void insertPlatformCandidate(
            Connection connection,
            String candidateId,
            UUID sourceSignId) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO platform_candidate(
                    id, discovery_run_id, station_candidate_id, source_sign_id,
                    suggested_label, world_name, block_x, block_y, block_z,
                    confidence, created_at
                ) VALUES (?, ?, 'STATION_TEST', ?, 'P1', 'world', 0, 64, 0, 0.5, ?)
                """)) {
            insert.setString(1, candidateId);
            insert.setString(2, discoveryRunId.toString());
            insert.setString(3, sourceSignId.toString());
            insert.setString(4, clock.instant().toString());
            insert.executeUpdate();
        }
    }

    private int count(String table) throws Exception {
        return scalarInt("SELECT COUNT(*) FROM " + table);
    }

    private int scalarInt(String sql) throws Exception {
        try (Connection connection = database.openConnection();
             Statement query = connection.createStatement();
             ResultSet result = query.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private String scalarString(String sql) throws Exception {
        try (Connection connection = database.openConnection();
             Statement query = connection.createStatement();
             ResultSet result = query.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static InfrastructureDiscoveryConfiguration configuration() {
        return new InfrastructureDiscoveryConfiguration(
                new InfrastructureDiscoveryConfiguration.StationEvidence(
                        List.of("Arrived:"), 16, 4),
                new InfrastructureDiscoveryConfiguration.StationClassification(
                        true, Set.of("reverse"), Set.of("continue")),
                new InfrastructureDiscoveryConfiguration.StationGrouping(48, 12),
                new InfrastructureDiscoveryConfiguration.Confidence(0.9, 0.4, 0.85, 0.7),
                new InfrastructureDiscoveryConfiguration.Rail(
                        InfrastructureDiscoveryConfiguration.Rail.Scope.SAVED_CHUNKS,
                        2,
                        1024,
                        1024,
                        10000));
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advanceSeconds(long seconds) {
            current = current.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    private static final class MutableWorldPolicy implements RailwayWorldPolicy {
        private volatile Set<String> activeWorlds;

        private MutableWorldPolicy(Set<String> activeWorlds) {
            replace(activeWorlds);
        }

        private void replace(Set<String> replacement) {
            activeWorlds = Set.copyOf(replacement);
        }

        @Override
        public boolean isActive(String worldName) {
            return activeWorlds.stream().anyMatch(worldName::equalsIgnoreCase);
        }

        @Override
        public Set<String> activeWorldNames() {
            return activeWorlds;
        }
    }
}
