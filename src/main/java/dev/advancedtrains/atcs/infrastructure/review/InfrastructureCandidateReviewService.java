package dev.advancedtrains.atcs.infrastructure.review;

import com.google.gson.Gson;
import dev.advancedtrains.atcs.discovery.InfrastructureEvidenceFingerprint;
import dev.advancedtrains.atcs.discovery.InfrastructureSourceSign;
import dev.advancedtrains.atcs.domain.BlockPosition;
import dev.advancedtrains.atcs.domain.DomainId;
import dev.advancedtrains.atcs.legacy.LegacySignType;
import dev.advancedtrains.atcs.persistence.SQLiteDatabase;
import dev.advancedtrains.atcs.world.RailwayWorldPolicy;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteException;

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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Reviews immutable discovery evidence and adopts approved candidates into digital authority records.
 */
public final class InfrastructureCandidateReviewService {
    private static final String SYSTEM_ACTOR = "candidate-review-reconciler";

    private final SQLiteDatabase database;
    private final RailwayWorldPolicy worldPolicy;
    private final Clock clock;
    private final Gson gson = new Gson();

    public InfrastructureCandidateReviewService(
            SQLiteDatabase database,
            RailwayWorldPolicy worldPolicy,
            Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.worldPolicy = Objects.requireNonNull(worldPolicy, "worldPolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public List<CandidateReviewItem> listLatestCandidates(
            Optional<CandidateKind> kind,
            Optional<CandidateDecisionStatus> status) throws SQLException {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
        invalidateStaleDecisions();
        try (Connection connection = database.openConnection()) {
            return loadLatestCandidates(connection).stream()
                    .filter(candidate -> kind.isEmpty() || kind.orElseThrow() == candidate.kind())
                    .filter(candidate -> status.isEmpty()
                            || status.orElseThrow() == candidate.decision().status())
                    .toList();
        }
    }

    public Optional<CandidateReviewItem> findLatestCandidate(UUID candidateId) throws SQLException {
        Objects.requireNonNull(candidateId, "candidateId");
        invalidateStaleDecisions();
        try (Connection connection = database.openConnection()) {
            return loadLatestCandidate(connection, candidateId);
        }
    }

    public CandidateDecisionResult decide(
            UUID candidateId,
            CandidateDecisionCommand command) throws SQLException {
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(command, "command");
        try (Connection connection = openImmediateWriteConnection()) {
            try {
                reconcileDecisions(connection);
                UUID latestRunId = loadLatestRunId(connection)
                        .orElseThrow(() -> new CandidateNotFoundException(
                                "No completed infrastructure discovery run exists"));
                if (!latestRunId.equals(command.expectedRunId())) {
                    throw new CandidateConflictException(
                            "The candidate discovery run is no longer current");
                }
                CandidateReviewItem candidate = loadLatestCandidate(connection, candidateId)
                        .orElseThrow(() -> new CandidateNotFoundException(
                                "Candidate is not part of the latest completed discovery run: " + candidateId));
                CandidateDecision before = candidate.decision();
                if (before.version() != command.expectedVersion()) {
                    throw new CandidateConflictException(
                            "Candidate decision changed from version " + command.expectedVersion()
                                    + " to " + before.version());
                }
                if (before.status() == CandidateDecisionStatus.STALE) {
                    throw new CandidateConflictException(
                            "The candidate evidence is stale; run discovery again before deciding");
                }
                validateReviewable(connection, candidate);
                int newVersion = before.version() + 1;
                boolean authorityChanged;
                CandidateDecisionStatus newStatus;
                Optional<CandidateAuthorityRole> newRole = Optional.empty();
                Optional<String> newTargetId = Optional.empty();
                Optional<String> newNote = command.note();
                if (command.action() == CandidateDecisionAction.INCLUDE) {
                    validateAdoptable(candidate);
                    CandidateAuthorityRole role = candidate.suggestedRole()
                            .orElseThrow(() -> new CandidateAdoptionException(
                                    "The candidate has no safe authority role"));
                    newRole = Optional.of(role);
                    newTargetId = Optional.of(adoptAuthority(
                            connection,
                            candidate,
                            role,
                            command.authority().orElseThrow()));
                    authorityChanged = true;
                    newStatus = CandidateDecisionStatus.INCLUDED;
                } else {
                    authorityChanged = disableLinkedAuthority(connection, candidate, before);
                    newStatus = command.action() == CandidateDecisionAction.IGNORE
                            ? CandidateDecisionStatus.IGNORED
                            : CandidateDecisionStatus.PENDING;
                    if (command.action() == CandidateDecisionAction.CLEAR) {
                        newNote = Optional.empty();
                    }
                }
                saveDecision(
                        connection,
                        candidate,
                        newStatus,
                        newRole,
                        newTargetId,
                        newNote,
                        Optional.empty(),
                        newVersion,
                        command.expectedVersion(),
                        command.actorId());
                if (command.action() != CandidateDecisionAction.INCLUDE) {
                    deleteAuthorityIdentityIfUnused(connection, before);
                }

                CandidateReviewItem after = loadLatestCandidate(connection, candidateId).orElseThrow();
                audit(
                        connection,
                        command.actorId(),
                        switch (command.action()) {
                            case INCLUDE -> "INFRASTRUCTURE_CANDIDATE_INCLUDED";
                            case IGNORE -> "INFRASTRUCTURE_CANDIDATE_IGNORED";
                            case CLEAR -> "INFRASTRUCTURE_CANDIDATE_CLEARED";
                        },
                        candidate,
                        before,
                        after.decision());
                connection.commit();
                return new CandidateDecisionResult(after, authorityChanged);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            if (isBusy(exception)) {
                throw new CandidateConflictException(
                        "Another candidate decision is being committed; reload and try again");
            }
            throw exception;
        }
    }

    public int invalidateStaleDecisions() throws SQLException {
        try (Connection connection = openImmediateWriteConnection()) {
            try {
                int invalidated = reconcileDecisions(connection);
                connection.commit();
                return invalidated;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            if (isBusy(exception)) {
                throw new CandidateConflictException(
                        "Candidate reconciliation is busy; reload and try again");
            }
            throw exception;
        }
    }

    private int reconcileDecisions(Connection connection) throws SQLException {
        Optional<UUID> latestRunId = loadLatestRunId(connection);
        List<DecisionBinding> decisions = loadDecisionBindings(connection);
        int invalidated = 0;
        for (DecisionBinding binding : decisions) {
            Optional<String> reason = staleReason(connection, binding, latestRunId);
            if (reason.isEmpty()) {
                continue;
            }
            CandidateDecision before = binding.decision();
            disableAuthorityBoundToDecision(connection, binding);
            if (before.status() == CandidateDecisionStatus.STALE) {
                continue;
            }
            int version = before.version() + 1;
            Instant now = Instant.now(clock);
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE infrastructure_candidate_decision
                    SET decision_status = 'STALE', stale_reason = ?, version = ?,
                        authority_role = NULL, target_id = NULL,
                        decided_by = ?, decided_at = ?
                    WHERE discovery_run_id = ? AND candidate_type = ? AND candidate_id = ?
                    """)) {
                update.setString(1, reason.orElseThrow());
                update.setInt(2, version);
                update.setString(3, SYSTEM_ACTOR);
                update.setString(4, now.toString());
                update.setString(5, binding.runId().toString());
                update.setString(6, binding.kind().name());
                update.setString(7, binding.candidateId().toString());
                update.executeUpdate();
            }
            deleteAuthorityIdentityIfUnused(connection, before);
            auditStale(connection, binding, reason.orElseThrow(), version, now);
            invalidated++;
        }
        return invalidated;
    }

    private Optional<String> staleReason(
            Connection connection,
            DecisionBinding binding,
            Optional<UUID> latestRunId) throws SQLException {
        if (latestRunId.isEmpty() || !latestRunId.orElseThrow().equals(binding.runId())) {
            return Optional.of("A newer infrastructure discovery run is authoritative");
        }
        if (!candidateExists(connection, binding)) {
            return Optional.of("The candidate no longer exists in its discovery run");
        }
        if (!worldPolicy.isActive(binding.worldName())) {
            return Optional.of("TrainCarts no longer enables the candidate world");
        }
        if (!binding.decisionFingerprint().equals(binding.snapshotFingerprint())
                || binding.decisionRevision() != binding.snapshotRevision()) {
            return Optional.of("The decision is not bound to the candidate evidence revision");
        }
        Optional<InfrastructureSourceSign> current = loadCurrentSource(connection, binding.sourceSignId());
        if (current.isEmpty()) {
            return Optional.of("The source sign is missing");
        }
        String currentFingerprint = InfrastructureEvidenceFingerprint.fingerprint(current.orElseThrow());
        if (!binding.snapshotFingerprint().equals(currentFingerprint)) {
            return Optional.of("The source sign evidence changed after discovery");
        }
        if (!authorityBindingExists(connection, binding, true)) {
            return Optional.of("The linked authority no longer matches this candidate evidence");
        }
        return Optional.empty();
    }

    private void validateReviewable(Connection connection, CandidateReviewItem candidate)
            throws SQLException {
        if (!worldPolicy.isActive(candidate.worldName())) {
            throw new CandidateAdoptionException(
                    "TrainCarts does not currently enable world " + candidate.worldName());
        }
        Optional<InfrastructureSourceSign> current = loadCurrentSource(connection, candidate.sourceSignId());
        if (current.isEmpty()) {
            throw new CandidateAdoptionException("The candidate source sign is missing");
        }
        String currentFingerprint = InfrastructureEvidenceFingerprint.fingerprint(current.orElseThrow());
        if (!candidate.evidenceFingerprint().equals(currentFingerprint)
                || candidate.evidenceRevision() != InfrastructureEvidenceFingerprint.REVISION) {
            throw new CandidateAdoptionException(
                    "The candidate source sign changed after discovery; run discovery again");
        }
    }

    private void validateAdoptable(CandidateReviewItem candidate) {
        CandidateAuthorityRole role = candidate.suggestedRole()
                .orElseThrow(() -> new CandidateAdoptionException(
                        "The candidate cannot be mapped to a safe authority role"));
        String instruction = candidate.candidateProperties()
                .getOrDefault("instruction", "")
                .strip()
                .toLowerCase(Locale.ROOT);
        if ("continue".equals(instruction) && role != CandidateAuthorityRole.MOTION_CONTROL) {
            throw new CandidateAdoptionException(
                    "A continue instruction can only become a motion control");
        }
        if ("reverse".equals(instruction) && role != CandidateAuthorityRole.REVERSING_CONTROL) {
            throw new CandidateAdoptionException(
                    "A reverse instruction can only become a reversing control");
        }
    }

    private String adoptAuthority(
            Connection connection,
            CandidateReviewItem candidate,
            CandidateAuthorityRole role,
            CandidateAuthorityInput input) throws SQLException {
        Optional<AuthorityRecord> existing = loadAuthorityBySource(
                connection, candidate.kind(), candidate.sourceSignId());
        String requestedTargetId = input.targetId()
                .map(value -> DomainId.of(value).toString())
                .orElseThrow(() -> new CandidateAdoptionException(
                        "Authority target ID is required"));
        String requestedDisplayName = input.displayName()
                .orElseThrow(() -> new CandidateAdoptionException(
                        "Authority display name is required"));
        if (existing.isPresent() && !existing.orElseThrow().id().equals(requestedTargetId)) {
            throw new CandidateAdoptionException(
                    "The source sign is already linked to authority " + existing.orElseThrow().id());
        }
        String targetId = existing.map(AuthorityRecord::id)
                .orElse(requestedTargetId);
        ensureTargetAvailable(connection, candidate.kind(), candidate.sourceSignId(), targetId);
        ensureSourceAvailable(connection, candidate, targetId);
        ensureLocationAvailable(connection, candidate, targetId);
        ensureAuthorityIdentityAvailable(connection, role, targetId);
        String displayName = requestedDisplayName;
        Instant now = Instant.now(clock);
        if (candidate.kind() == CandidateKind.DEPARTURE) {
            if (role != CandidateAuthorityRole.DEPARTURE_POINT) {
                throw new CandidateAdoptionException(
                        "A departure candidate can only create a departure point");
            }
            upsertDepartureAuthority(connection, candidate, targetId, displayName, existing, now);
        } else {
            upsertTrackControlAuthority(connection, candidate, role, targetId, displayName, existing, now);
        }
        ensureAuthorityIdentity(connection, role, targetId);
        return targetId;
    }

    private void upsertDepartureAuthority(
            Connection connection,
            CandidateReviewItem candidate,
            String targetId,
            String displayName,
            Optional<AuthorityRecord> existing,
            Instant now) throws SQLException {
        if (existing.isPresent()) {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE departure_point
                    SET display_name = ?, world_name = ?, block_x = ?, block_y = ?, block_z = ?,
                        direction_hint = ?, source_discovery_run_id = ?, source_candidate_id = ?,
                        source_sign_id = ?, source_evidence_fingerprint = ?, source_evidence_revision = ?,
                        enabled = 1, version = version + 1, updated_at = ?
                    WHERE id = ?
                    """)) {
                update.setString(1, displayName);
                setCandidatePosition(update, 2, candidate);
                setNullable(update, 6, candidate.candidateProperties().get("directionHint"));
                setSourceBinding(update, 7, candidate);
                update.setString(12, now.toString());
                update.setString(13, targetId);
                update.executeUpdate();
            }
            return;
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO departure_point(
                    id, display_name, world_name, block_x, block_y, block_z, direction_hint,
                    source_discovery_run_id, source_candidate_id, source_sign_id,
                    source_evidence_fingerprint, source_evidence_revision,
                    enabled, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1, ?, ?)
                """)) {
            insert.setString(1, targetId);
            insert.setString(2, displayName);
            setCandidatePosition(insert, 3, candidate);
            setNullable(insert, 7, candidate.candidateProperties().get("directionHint"));
            setSourceBinding(insert, 8, candidate);
            insert.setString(13, now.toString());
            insert.setString(14, now.toString());
            insert.executeUpdate();
        }
    }

    private void upsertTrackControlAuthority(
            Connection connection,
            CandidateReviewItem candidate,
            CandidateAuthorityRole role,
            String targetId,
            String displayName,
            Optional<AuthorityRecord> existing,
            Instant now) throws SQLException {
        String controlRole = switch (role) {
            case MOTION_CONTROL -> "MOTION_CONTROL";
            case REVERSING_CONTROL -> "REVERSING_CONTROL";
            case DEPARTURE_POINT -> throw new CandidateAdoptionException(
                    "A departure role cannot create a track control point");
        };
        if (existing.isPresent()) {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE track_control_point
                    SET display_name = ?, control_role = ?,
                        world_name = ?, block_x = ?, block_y = ?, block_z = ?,
                        source_discovery_run_id = ?, source_candidate_id = ?,
                        source_evidence_fingerprint = ?, source_evidence_revision = ?,
                        enabled = 1, version = version + 1, updated_at = ?
                    WHERE id = ?
                    """)) {
                update.setString(1, displayName);
                update.setString(2, controlRole);
                setCandidatePosition(update, 3, candidate);
                update.setString(7, candidate.discoveryRunId().toString());
                update.setString(8, candidate.candidateId().toString());
                update.setString(9, candidate.evidenceFingerprint());
                update.setInt(10, candidate.evidenceRevision());
                update.setString(11, now.toString());
                update.setString(12, targetId);
                update.executeUpdate();
            }
            return;
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO track_control_point(
                    id, display_name, control_role,
                    world_name, block_x, block_y, block_z, legacy_sign_id,
                    source_discovery_run_id, source_candidate_id,
                    source_evidence_fingerprint, source_evidence_revision,
                    enabled, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1, ?, ?)
                """)) {
            insert.setString(1, targetId);
            insert.setString(2, displayName);
            insert.setString(3, controlRole);
            setCandidatePosition(insert, 4, candidate);
            insert.setString(8, candidate.sourceSignId().toString());
            insert.setString(9, candidate.discoveryRunId().toString());
            insert.setString(10, candidate.candidateId().toString());
            insert.setString(11, candidate.evidenceFingerprint());
            insert.setInt(12, candidate.evidenceRevision());
            insert.setString(13, now.toString());
            insert.setString(14, now.toString());
            insert.executeUpdate();
        }
    }

    private boolean disableLinkedAuthority(
            Connection connection,
            CandidateReviewItem candidate,
            CandidateDecision decision)
            throws SQLException {
        if (decision.authorityRole().isEmpty() || decision.targetId().isEmpty()) {
            return false;
        }
        String table = decision.authorityRole().orElseThrow() == CandidateAuthorityRole.DEPARTURE_POINT
                ? "departure_point"
                : "track_control_point";
        String sourceColumn = "departure_point".equals(table) ? "source_sign_id" : "legacy_sign_id";
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + table + " SET enabled = 0, version = version + 1, updated_at = ? "
                        + "WHERE id = ? AND enabled = 1 "
                        + "AND source_discovery_run_id = ? AND source_candidate_id = ? "
                        + "AND " + sourceColumn + " = ? "
                        + "AND source_evidence_fingerprint = ? AND source_evidence_revision = ?")) {
            update.setString(1, Instant.now(clock).toString());
            update.setString(2, decision.targetId().orElseThrow());
            update.setString(3, candidate.discoveryRunId().toString());
            update.setString(4, candidate.candidateId().toString());
            update.setString(5, candidate.sourceSignId().toString());
            update.setString(6, candidate.evidenceFingerprint());
            update.setInt(7, candidate.evidenceRevision());
            if (update.executeUpdate() == 1) {
                return true;
            }
        }
        if (authorityBindingExists(connection, candidate, decision, false)) {
            return false;
        }
        throw new CandidateConflictException(
                "The linked authority no longer matches this candidate evidence");
    }

    private boolean disableAuthorityBoundToDecision(
            Connection connection,
            DecisionBinding binding) throws SQLException {
        CandidateDecision decision = binding.decision();
        if (decision.authorityRole().isEmpty() || decision.targetId().isEmpty()) {
            return false;
        }
        String table = decision.authorityRole().orElseThrow() == CandidateAuthorityRole.DEPARTURE_POINT
                ? "departure_point"
                : "track_control_point";
        String sourceColumn = "departure_point".equals(table) ? "source_sign_id" : "legacy_sign_id";
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + table + " SET enabled = 0, version = version + 1, updated_at = ? "
                        + "WHERE id = ? AND enabled = 1 "
                        + "AND source_discovery_run_id = ? AND source_candidate_id = ? "
                        + "AND " + sourceColumn + " = ? "
                        + "AND source_evidence_fingerprint = ? AND source_evidence_revision = ?")) {
            update.setString(1, Instant.now(clock).toString());
            update.setString(2, decision.targetId().orElseThrow());
            update.setString(3, binding.runId().toString());
            update.setString(4, binding.candidateId().toString());
            update.setString(5, binding.sourceSignId().toString());
            update.setString(6, binding.decisionFingerprint());
            update.setInt(7, binding.decisionRevision());
            return update.executeUpdate() == 1;
        }
    }

    private void saveDecision(
            Connection connection,
            CandidateReviewItem candidate,
            CandidateDecisionStatus status,
            Optional<CandidateAuthorityRole> role,
            Optional<String> targetId,
            Optional<String> note,
            Optional<String> staleReason,
            int version,
            int expectedVersion,
            String actorId) throws SQLException {
        try (PreparedStatement upsert = connection.prepareStatement("""
                INSERT INTO infrastructure_candidate_decision(
                    discovery_run_id, candidate_type, candidate_id, source_sign_id,
                    evidence_fingerprint, evidence_revision, decision_status,
                    authority_role, target_id, note, stale_reason,
                    version, decided_by, decided_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(discovery_run_id, candidate_type, candidate_id) DO UPDATE SET
                    source_sign_id = excluded.source_sign_id,
                    evidence_fingerprint = excluded.evidence_fingerprint,
                    evidence_revision = excluded.evidence_revision,
                    decision_status = excluded.decision_status,
                    authority_role = excluded.authority_role,
                    target_id = excluded.target_id,
                    note = excluded.note,
                    stale_reason = excluded.stale_reason,
                    version = excluded.version,
                    decided_by = excluded.decided_by,
                    decided_at = excluded.decided_at
                WHERE infrastructure_candidate_decision.version = ?
                """)) {
            upsert.setString(1, candidate.discoveryRunId().toString());
            upsert.setString(2, candidate.kind().name());
            upsert.setString(3, candidate.candidateId().toString());
            upsert.setString(4, candidate.sourceSignId().toString());
            upsert.setString(5, candidate.evidenceFingerprint());
            upsert.setInt(6, candidate.evidenceRevision());
            upsert.setString(7, status.name());
            setOptionalEnum(upsert, 8, role);
            setOptionalString(upsert, 9, targetId);
            setOptionalString(upsert, 10, note);
            setOptionalString(upsert, 11, staleReason);
            upsert.setInt(12, version);
            upsert.setString(13, actorId);
            upsert.setString(14, Instant.now(clock).toString());
            upsert.setInt(15, expectedVersion);
            if (upsert.executeUpdate() != 1) {
                throw new CandidateConflictException(
                        "Candidate decision changed before it could be committed");
            }
        }
    }

    private List<CandidateReviewItem> loadLatestCandidates(Connection connection) throws SQLException {
        Optional<UUID> latestRunId = loadLatestRunId(connection);
        if (latestRunId.isEmpty()) {
            return List.of();
        }
        UUID runId = latestRunId.orElseThrow();
        Map<UUID, Map<String, String>> attributes = loadSnapshotAttributes(connection, runId);
        Map<CandidateKey, List<CandidateEvidence>> evidence = loadCandidateEvidence(connection, runId);
        List<CandidateReviewItem> candidates = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT run.id, run.completed_at,
                       candidate.candidate_type, candidate.candidate_id, candidate.source_sign_id,
                       candidate.world_name, candidate.block_x, candidate.block_y, candidate.block_z,
                       candidate.confidence, candidate.suggested_role,
                       candidate.property_1, candidate.property_2, candidate.property_3,
                       candidate.property_4, candidate.property_5,
                       snapshot.sign_side, snapshot.action_type,
                       snapshot.raw_line_1, snapshot.raw_line_2,
                       snapshot.raw_line_3, snapshot.raw_line_4,
                       snapshot.evidence_fingerprint, snapshot.evidence_revision,
                       decision.decision_status, decision.authority_role, decision.target_id,
                       decision.note, decision.version, decision.decided_by,
                       decision.decided_at, decision.stale_reason,
                       COALESCE(departure.display_name, control.display_name)
                FROM (
                    SELECT discovery_run_id, 'DEPARTURE' AS candidate_type,
                           id AS candidate_id, source_sign_id,
                           world_name, block_x, block_y, block_z, confidence,
                           NULL AS suggested_role,
                           direction_hint AS property_1,
                           spawn_pattern AS property_2,
                           interval_text AS property_3,
                           CAST(launch_force AS TEXT) AS property_4,
                           CAST(always_powered AS TEXT) AS property_5
                    FROM departure_candidate
                    UNION ALL
                    SELECT discovery_run_id, 'TRACK_CONTROL' AS candidate_type,
                           id AS candidate_id, source_sign_id,
                           world_name, block_x, block_y, block_z, confidence,
                           suggested_role,
                           instruction_text AS property_1,
                           delay_text AS property_2,
                           launch_distance_text AS property_3,
                           center_offset_text AS property_4,
                           NULL AS property_5
                    FROM track_control_candidate
                ) candidate
                JOIN infrastructure_discovery_run run ON run.id = candidate.discovery_run_id
                JOIN infrastructure_source_sign_snapshot snapshot
                  ON snapshot.discovery_run_id = candidate.discovery_run_id
                 AND snapshot.source_sign_id = candidate.source_sign_id
                LEFT JOIN infrastructure_candidate_decision decision
                  ON decision.discovery_run_id = candidate.discovery_run_id
                 AND decision.candidate_type = candidate.candidate_type
                 AND decision.candidate_id = candidate.candidate_id
                LEFT JOIN departure_point departure
                  ON candidate.candidate_type = 'DEPARTURE' AND departure.id = decision.target_id
                LEFT JOIN track_control_point control
                  ON candidate.candidate_type = 'TRACK_CONTROL' AND control.id = decision.target_id
                WHERE candidate.discovery_run_id = ?
                ORDER BY candidate.candidate_type, candidate.world_name,
                         candidate.block_x, candidate.block_y, candidate.block_z,
                         candidate.candidate_id
                """)) {
            query.setString(1, runId.toString());
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    candidates.add(readCandidate(result, attributes, evidence));
                }
            }
        }
        List<CandidateReviewItem> visible = new ArrayList<>();
        for (CandidateReviewItem candidate : candidates) {
            if (!worldPolicy.isActive(candidate.worldName())) {
                continue;
            }
            visible.add(markUndecidedStale(connection, candidate));
        }
        return List.copyOf(visible);
    }

    private CandidateReviewItem markUndecidedStale(
            Connection connection,
            CandidateReviewItem candidate) throws SQLException {
        if (candidate.decision().version() != 0) {
            return candidate;
        }
        Optional<String> reason = currentEvidenceIssue(connection, candidate);
        if (reason.isEmpty()) {
            return candidate;
        }
        CandidateDecision stale = new CandidateDecision(
                CandidateDecisionStatus.STALE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                Optional.empty(),
                Optional.empty(),
                reason);
        return new CandidateReviewItem(
                candidate.discoveryRunId(),
                candidate.discoveredAt(),
                candidate.kind(),
                candidate.candidateId(),
                candidate.sourceSignId(),
                candidate.worldName(),
                candidate.blockX(),
                candidate.blockY(),
                candidate.blockZ(),
                candidate.signSide(),
                candidate.actionType(),
                candidate.rawLines(),
                candidate.attributes(),
                candidate.confidence(),
                candidate.suggestedRole(),
                candidate.candidateProperties(),
                candidate.evidence(),
                candidate.evidenceFingerprint(),
                candidate.evidenceRevision(),
                stale);
    }

    private Optional<String> currentEvidenceIssue(
            Connection connection,
            CandidateReviewItem candidate) throws SQLException {
        Optional<InfrastructureSourceSign> current = loadCurrentSource(
                connection, candidate.sourceSignId());
        if (current.isEmpty()) {
            return Optional.of("The source sign is missing");
        }
        String currentFingerprint = InfrastructureEvidenceFingerprint.fingerprint(current.orElseThrow());
        if (!candidate.evidenceFingerprint().equals(currentFingerprint)
                || candidate.evidenceRevision() != InfrastructureEvidenceFingerprint.REVISION) {
            return Optional.of("The source sign evidence changed after discovery");
        }
        return Optional.empty();
    }

    private Optional<CandidateReviewItem> loadLatestCandidate(
            Connection connection,
            UUID candidateId) throws SQLException {
        return loadLatestCandidates(connection).stream()
                .filter(candidate -> candidate.candidateId().equals(candidateId))
                .findFirst();
    }

    private CandidateReviewItem readCandidate(
            ResultSet result,
            Map<UUID, Map<String, String>> attributes,
            Map<CandidateKey, List<CandidateEvidence>> evidence) throws SQLException {
        UUID runId = UUID.fromString(result.getString(1));
        CandidateKind kind = CandidateKind.valueOf(result.getString(3));
        UUID candidateId = UUID.fromString(result.getString(4));
        UUID sourceSignId = UUID.fromString(result.getString(5));
        Map<String, String> properties = new LinkedHashMap<>();
        if (kind == CandidateKind.DEPARTURE) {
            putIfPresent(properties, "directionHint", result.getString(12));
            putIfPresent(properties, "spawnPattern", result.getString(13));
            putIfPresent(properties, "interval", result.getString(14));
            putIfPresent(properties, "launchForce", result.getString(15));
            String alwaysPowered = result.getString(16);
            if (alwaysPowered != null) {
                properties.put("alwaysPowered", "1".equals(alwaysPowered) ? "true" : "false");
            }
        } else {
            putIfPresent(properties, "instruction", result.getString(12));
            putIfPresent(properties, "delay", result.getString(13));
            putIfPresent(properties, "launchDistance", result.getString(14));
            putIfPresent(properties, "centerOffset", result.getString(15));
        }
        Optional<CandidateAuthorityRole> suggestedRole = kind == CandidateKind.DEPARTURE
                ? Optional.of(CandidateAuthorityRole.DEPARTURE_POINT)
                : mapTrackControlRole(result.getString(11));
        CandidateDecision decision = readDecision(result);
        CandidateKey key = new CandidateKey(kind, candidateId);
        return new CandidateReviewItem(
                runId,
                Instant.parse(result.getString(2)),
                kind,
                candidateId,
                sourceSignId,
                result.getString(6),
                result.getInt(7),
                result.getInt(8),
                result.getInt(9),
                result.getString(17),
                result.getString(18),
                List.of(
                        result.getString(19),
                        result.getString(20),
                        result.getString(21),
                        result.getString(22)),
                attributes.getOrDefault(sourceSignId, Map.of()),
                result.getDouble(10),
                suggestedRole,
                properties,
                evidence.getOrDefault(key, List.of()),
                result.getString(23),
                result.getInt(24),
                decision);
    }

    private static CandidateDecision readDecision(ResultSet result) throws SQLException {
        String status = result.getString(25);
        if (status == null) {
            return CandidateDecision.pending();
        }
        String decidedAt = result.getString(31);
        return new CandidateDecision(
                CandidateDecisionStatus.valueOf(status),
                optionalEnum(CandidateAuthorityRole.class, result.getString(26)),
                Optional.ofNullable(result.getString(27)),
                Optional.ofNullable(result.getString(33)),
                Optional.ofNullable(result.getString(28)),
                result.getInt(29),
                Optional.ofNullable(result.getString(30)),
                decidedAt == null ? Optional.empty() : Optional.of(Instant.parse(decidedAt)),
                Optional.ofNullable(result.getString(32)));
    }

    private Map<UUID, Map<String, String>> loadSnapshotAttributes(
            Connection connection,
            UUID runId) throws SQLException {
        Map<UUID, Map<String, String>> attributes = new LinkedHashMap<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT source_sign_id, attribute_key, attribute_value
                FROM infrastructure_source_sign_snapshot_attribute
                WHERE discovery_run_id = ?
                ORDER BY source_sign_id, attribute_key
                """)) {
            query.setString(1, runId.toString());
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    UUID signId = UUID.fromString(result.getString(1));
                    attributes.computeIfAbsent(signId, ignored -> new LinkedHashMap<>())
                            .put(result.getString(2), result.getString(3));
                }
            }
        }
        return attributes;
    }

    private Map<CandidateKey, List<CandidateEvidence>> loadCandidateEvidence(
            Connection connection,
            UUID runId) throws SQLException {
        Map<CandidateKey, List<CandidateEvidence>> evidence = new LinkedHashMap<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT candidate_type, candidate_id, evidence_kind, source_sign_id,
                       distance_blocks, evidence_text
                FROM infrastructure_candidate_evidence
                WHERE discovery_run_id = ?
                  AND candidate_type IN ('DEPARTURE', 'TRACK_CONTROL')
                ORDER BY candidate_type, candidate_id, evidence_kind, source_sign_id
                """)) {
            query.setString(1, runId.toString());
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    CandidateKey key = new CandidateKey(
                            CandidateKind.valueOf(result.getString(1)),
                            UUID.fromString(result.getString(2)));
                    double distance = result.getDouble(5);
                    Optional<Double> optionalDistance = result.wasNull()
                            ? Optional.empty()
                            : Optional.of(distance);
                    evidence.computeIfAbsent(key, ignored -> new ArrayList<>()).add(
                            new CandidateEvidence(
                                    result.getString(3),
                                    UUID.fromString(result.getString(4)),
                                    optionalDistance,
                                    Optional.ofNullable(result.getString(6))));
                }
            }
        }
        return evidence;
    }

    private Optional<InfrastructureSourceSign> loadCurrentSource(
            Connection connection,
            UUID sourceSignId) throws SQLException {
        SourceCore core;
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT action_type, world_name, block_x, block_y, block_z,
                       sign_side, header_line,
                       raw_line_1, raw_line_2, raw_line_3, raw_line_4,
                       parser_version, present
                FROM legacy_sign_inventory
                WHERE id = ?
                """)) {
            query.setString(1, sourceSignId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next() || result.getInt(13) != 1) {
                    return Optional.empty();
                }
                core = new SourceCore(
                        LegacySignType.valueOf(result.getString(1)),
                        new BlockPosition(
                                result.getString(2),
                                result.getInt(3),
                                result.getInt(4),
                                result.getInt(5)),
                        result.getString(6),
                        result.getString(7),
                        List.of(
                                result.getString(8),
                                result.getString(9),
                                result.getString(10),
                                result.getString(11)),
                        result.getInt(12));
            }
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT attribute_key, attribute_value
                FROM legacy_sign_attribute
                WHERE sign_id = ?
                ORDER BY attribute_key
                """)) {
            query.setString(1, sourceSignId.toString());
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    attributes.put(result.getString(1), result.getString(2));
                }
            }
        }
        return Optional.of(new InfrastructureSourceSign(
                sourceSignId,
                core.type(),
                core.position(),
                core.signSide(),
                core.headerLine(),
                core.rawLines(),
                core.parserVersion(),
                attributes));
    }

    private List<DecisionBinding> loadDecisionBindings(Connection connection) throws SQLException {
        List<DecisionBinding> bindings = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT decision.discovery_run_id, decision.candidate_type,
                       decision.candidate_id, decision.source_sign_id,
                       decision.evidence_fingerprint, decision.evidence_revision,
                       decision.decision_status, decision.authority_role,
                       decision.target_id, decision.note, decision.version,
                       decision.decided_by, decision.decided_at, decision.stale_reason,
                       snapshot.world_name, snapshot.evidence_fingerprint,
                       snapshot.evidence_revision,
                       COALESCE(departure.display_name, control.display_name)
                FROM infrastructure_candidate_decision decision
                JOIN infrastructure_source_sign_snapshot snapshot
                  ON snapshot.discovery_run_id = decision.discovery_run_id
                 AND snapshot.source_sign_id = decision.source_sign_id
                LEFT JOIN departure_point departure
                  ON decision.authority_role = 'DEPARTURE_POINT'
                 AND departure.id = decision.target_id
                LEFT JOIN track_control_point control
                  ON decision.authority_role IN ('MOTION_CONTROL', 'REVERSING_CONTROL')
                 AND control.id = decision.target_id
                ORDER BY decision.discovery_run_id, decision.candidate_type, decision.candidate_id
                """)) {
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    String decidedAt = result.getString(13);
                    CandidateDecision decision = new CandidateDecision(
                            CandidateDecisionStatus.valueOf(result.getString(7)),
                            optionalEnum(CandidateAuthorityRole.class, result.getString(8)),
                            Optional.ofNullable(result.getString(9)),
                            Optional.ofNullable(result.getString(18)),
                            Optional.ofNullable(result.getString(10)),
                            result.getInt(11),
                            Optional.ofNullable(result.getString(12)),
                            decidedAt == null ? Optional.empty() : Optional.of(Instant.parse(decidedAt)),
                            Optional.ofNullable(result.getString(14)));
                    bindings.add(new DecisionBinding(
                            UUID.fromString(result.getString(1)),
                            CandidateKind.valueOf(result.getString(2)),
                            UUID.fromString(result.getString(3)),
                            UUID.fromString(result.getString(4)),
                            result.getString(15),
                            result.getString(5),
                            result.getInt(6),
                            result.getString(16),
                            result.getInt(17),
                            decision));
                }
            }
        }
        return bindings;
    }

    private boolean candidateExists(Connection connection, DecisionBinding binding) throws SQLException {
        String table = binding.kind() == CandidateKind.DEPARTURE
                ? "departure_candidate"
                : "track_control_candidate";
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM " + table + " WHERE discovery_run_id = ? AND id = ? AND source_sign_id = ?")) {
            query.setString(1, binding.runId().toString());
            query.setString(2, binding.candidateId().toString());
            query.setString(3, binding.sourceSignId().toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    private Optional<UUID> loadLatestRunId(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT id
                FROM infrastructure_discovery_run
                WHERE status = 'COMPLETED'
                ORDER BY completed_at DESC, id DESC
                LIMIT 1
                """);
             ResultSet result = query.executeQuery()) {
            return result.next()
                    ? Optional.of(UUID.fromString(result.getString(1)))
                    : Optional.empty();
        }
    }

    private Optional<AuthorityRecord> loadAuthorityBySource(
            Connection connection,
            CandidateKind kind,
            UUID sourceSignId) throws SQLException {
        String table = kind == CandidateKind.DEPARTURE ? "departure_point" : "track_control_point";
        String sourceColumn = kind == CandidateKind.DEPARTURE ? "source_sign_id" : "legacy_sign_id";
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT id, display_name FROM " + table + " WHERE " + sourceColumn + " = ?")) {
            query.setString(1, sourceSignId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next()
                        ? Optional.of(new AuthorityRecord(result.getString(1), result.getString(2)))
                        : Optional.empty();
            }
        }
    }

    private void ensureTargetAvailable(
            Connection connection,
            CandidateKind kind,
            UUID sourceSignId,
            String targetId) throws SQLException {
        String expectedTable = kind == CandidateKind.DEPARTURE ? "departure_point" : "track_control_point";
        for (String table : List.of("departure_point", "track_control_point")) {
            String sourceColumn = "departure_point".equals(table) ? "source_sign_id" : "legacy_sign_id";
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT " + sourceColumn + " FROM " + table + " WHERE id = ?")) {
                query.setString(1, targetId);
                try (ResultSet result = query.executeQuery()) {
                    if (!result.next()) {
                        continue;
                    }
                    String linkedSource = result.getString(1);
                    if (!table.equals(expectedTable)
                            || linkedSource == null
                            || !linkedSource.equals(sourceSignId.toString())) {
                        throw new CandidateAdoptionException(
                                "Authority target ID is already in use: " + targetId);
                    }
                }
            }
        }
    }

    private void ensureSourceAvailable(
            Connection connection,
            CandidateReviewItem candidate,
            String targetId) throws SQLException {
        String expectedTable = candidate.kind() == CandidateKind.DEPARTURE
                ? "departure_point"
                : "track_control_point";
        for (String table : List.of("departure_point", "track_control_point")) {
            String sourceColumn = "departure_point".equals(table) ? "source_sign_id" : "legacy_sign_id";
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT id FROM " + table + " WHERE " + sourceColumn + " = ?")) {
                query.setString(1, candidate.sourceSignId().toString());
                try (ResultSet result = query.executeQuery()) {
                    if (result.next()
                            && (!table.equals(expectedTable) || !targetId.equals(result.getString(1)))) {
                        throw new CandidateAdoptionException(
                                "The source sign is already linked to another authority record");
                    }
                }
            }
        }
    }

    private void ensureLocationAvailable(
            Connection connection,
            CandidateReviewItem candidate,
            String targetId) throws SQLException {
        String expectedTable = candidate.kind() == CandidateKind.DEPARTURE
                ? "departure_point"
                : "track_control_point";
        for (String table : List.of("departure_point", "track_control_point")) {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT id FROM " + table + " "
                            + "WHERE world_name = ? COLLATE NOCASE "
                            + "AND block_x = ? AND block_y = ? AND block_z = ?")) {
                query.setString(1, candidate.worldName());
                query.setInt(2, candidate.blockX());
                query.setInt(3, candidate.blockY());
                query.setInt(4, candidate.blockZ());
                try (ResultSet result = query.executeQuery()) {
                    if (result.next()
                            && (!table.equals(expectedTable) || !targetId.equals(result.getString(1)))) {
                        throw new CandidateAdoptionException(
                                "Another authority record already uses the candidate location");
                    }
                }
            }
        }
    }

    private void ensureAuthorityIdentityAvailable(
            Connection connection,
            CandidateAuthorityRole role,
            String targetId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT authority_role
                FROM infrastructure_authority_identity
                WHERE target_id = ?
                """)) {
            query.setString(1, targetId);
            try (ResultSet result = query.executeQuery()) {
                if (result.next() && !role.name().equals(result.getString(1))) {
                    throw new CandidateAdoptionException(
                            "Authority target ID is already bound to another audited role");
                }
            }
        }
    }

    private void ensureAuthorityIdentity(
            Connection connection,
            CandidateAuthorityRole role,
            String targetId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO infrastructure_authority_identity(
                    authority_role, target_id, departure_point_id, track_control_point_id
                ) VALUES (?, ?, ?, ?)
                ON CONFLICT(authority_role, target_id) DO NOTHING
                """)) {
            insert.setString(1, role.name());
            insert.setString(2, targetId);
            if (role == CandidateAuthorityRole.DEPARTURE_POINT) {
                insert.setString(3, targetId);
                insert.setNull(4, Types.VARCHAR);
            } else {
                insert.setNull(3, Types.VARCHAR);
                insert.setString(4, targetId);
            }
            insert.executeUpdate();
        }
    }

    private void deleteAuthorityIdentityIfUnused(
            Connection connection,
            CandidateDecision decision) throws SQLException {
        if (decision.authorityRole().isEmpty() || decision.targetId().isEmpty()) {
            return;
        }
        try (PreparedStatement delete = connection.prepareStatement("""
                DELETE FROM infrastructure_authority_identity
                WHERE authority_role = ? AND target_id = ?
                  AND NOT EXISTS (
                      SELECT 1
                      FROM infrastructure_candidate_decision
                      WHERE authority_role = ? AND target_id = ?
                  )
                """)) {
            String role = decision.authorityRole().orElseThrow().name();
            String targetId = decision.targetId().orElseThrow();
            delete.setString(1, role);
            delete.setString(2, targetId);
            delete.setString(3, role);
            delete.setString(4, targetId);
            delete.executeUpdate();
        }
    }

    private boolean authorityBindingExists(
            Connection connection,
            CandidateReviewItem candidate,
            CandidateDecision decision,
            boolean requireEnabled) throws SQLException {
        if (decision.authorityRole().isEmpty() || decision.targetId().isEmpty()) {
            return true;
        }
        String table = decision.authorityRole().orElseThrow() == CandidateAuthorityRole.DEPARTURE_POINT
                ? "departure_point"
                : "track_control_point";
        String sourceColumn = "departure_point".equals(table) ? "source_sign_id" : "legacy_sign_id";
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM " + table + " WHERE id = ? "
                        + (requireEnabled ? "AND enabled = 1 " : "")
                        + "AND source_discovery_run_id = ? AND source_candidate_id = ? "
                        + "AND " + sourceColumn + " = ? "
                        + "AND source_evidence_fingerprint = ? AND source_evidence_revision = ?")) {
            query.setString(1, decision.targetId().orElseThrow());
            query.setString(2, candidate.discoveryRunId().toString());
            query.setString(3, candidate.candidateId().toString());
            query.setString(4, candidate.sourceSignId().toString());
            query.setString(5, candidate.evidenceFingerprint());
            query.setInt(6, candidate.evidenceRevision());
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean authorityBindingExists(
            Connection connection,
            DecisionBinding binding,
            boolean requireEnabled) throws SQLException {
        CandidateDecision decision = binding.decision();
        if (decision.authorityRole().isEmpty() || decision.targetId().isEmpty()) {
            return true;
        }
        String table = decision.authorityRole().orElseThrow() == CandidateAuthorityRole.DEPARTURE_POINT
                ? "departure_point"
                : "track_control_point";
        String sourceColumn = "departure_point".equals(table) ? "source_sign_id" : "legacy_sign_id";
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM " + table + " WHERE id = ? "
                        + (requireEnabled ? "AND enabled = 1 " : "")
                        + "AND source_discovery_run_id = ? AND source_candidate_id = ? "
                        + "AND " + sourceColumn + " = ? "
                        + "AND source_evidence_fingerprint = ? AND source_evidence_revision = ?")) {
            query.setString(1, decision.targetId().orElseThrow());
            query.setString(2, binding.runId().toString());
            query.setString(3, binding.candidateId().toString());
            query.setString(4, binding.sourceSignId().toString());
            query.setString(5, binding.decisionFingerprint());
            query.setInt(6, binding.decisionRevision());
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    private void audit(
            Connection connection,
            String actorId,
            String action,
            CandidateReviewItem candidate,
            CandidateDecision before,
            CandidateDecision after) throws SQLException {
        insertAudit(
                connection,
                "WEB",
                actorId,
                action,
                candidate.candidateId().toString(),
                gson.toJson(decisionAuditValue(before)),
                gson.toJson(decisionAuditValue(after)));
    }

    private void auditStale(
            Connection connection,
            DecisionBinding binding,
            String staleReason,
            int version,
            Instant now) throws SQLException {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", CandidateDecisionStatus.STALE.name());
        after.put("version", version);
        after.put("staleReason", staleReason);
        after.put("decidedAt", now.toString());
        insertAudit(
                connection,
                "SYSTEM",
                SYSTEM_ACTOR,
                "INFRASTRUCTURE_CANDIDATE_STALE",
                binding.candidateId().toString(),
                gson.toJson(decisionAuditValue(binding.decision())),
                gson.toJson(after));
    }

    private void insertAudit(
            Connection connection,
            String actorType,
            String actorId,
            String action,
            String targetId,
            String beforeJson,
            String afterJson) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO audit_record(
                    id, correlation_id, actor_type, actor_id, action,
                    target_type, target_id, before_json, after_json, created_at
                ) VALUES (?, ?, ?, ?, ?, 'INFRASTRUCTURE_CANDIDATE', ?, ?, ?, ?)
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, UUID.randomUUID().toString());
            insert.setString(3, actorType);
            insert.setString(4, actorId);
            insert.setString(5, action);
            insert.setString(6, targetId);
            insert.setString(7, beforeJson);
            insert.setString(8, afterJson);
            insert.setString(9, Instant.now(clock).toString());
            insert.executeUpdate();
        }
    }

    private static Map<String, Object> decisionAuditValue(CandidateDecision decision) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("status", decision.status().name());
        value.put("authorityRole", decision.authorityRole().map(Enum::name).orElse(null));
        value.put("targetId", decision.targetId().orElse(null));
        value.put("authorityDisplayName", decision.authorityDisplayName().orElse(null));
        value.put("note", decision.note().orElse(null));
        value.put("version", decision.version());
        value.put("decidedBy", decision.decidedBy().orElse(null));
        value.put("decidedAt", decision.decidedAt().map(Instant::toString).orElse(null));
        value.put("staleReason", decision.staleReason().orElse(null));
        return value;
    }

    private static Optional<CandidateAuthorityRole> mapTrackControlRole(String role) {
        if (role == null) {
            return Optional.empty();
        }
        return switch (role) {
            case "REVERSING_CONTROL" -> Optional.of(CandidateAuthorityRole.REVERSING_CONTROL);
            case "THROUGH_MOTION_CONTROL" -> Optional.of(CandidateAuthorityRole.MOTION_CONTROL);
            default -> Optional.empty();
        };
    }

    private static void setCandidatePosition(
            PreparedStatement statement,
            int firstIndex,
            CandidateReviewItem candidate) throws SQLException {
        statement.setString(firstIndex, candidate.worldName());
        statement.setInt(firstIndex + 1, candidate.blockX());
        statement.setInt(firstIndex + 2, candidate.blockY());
        statement.setInt(firstIndex + 3, candidate.blockZ());
    }

    private static void setSourceBinding(
            PreparedStatement statement,
            int firstIndex,
            CandidateReviewItem candidate) throws SQLException {
        statement.setString(firstIndex, candidate.discoveryRunId().toString());
        statement.setString(firstIndex + 1, candidate.candidateId().toString());
        statement.setString(firstIndex + 2, candidate.sourceSignId().toString());
        statement.setString(firstIndex + 3, candidate.evidenceFingerprint());
        statement.setInt(firstIndex + 4, candidate.evidenceRevision());
    }

    private static void setNullable(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null || value.isBlank()) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
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

    private static <E extends Enum<E>> void setOptionalEnum(
            PreparedStatement statement,
            int index,
            Optional<E> value) throws SQLException {
        if (value.isPresent()) {
            statement.setString(index, value.orElseThrow().name());
        } else {
            statement.setNull(index, Types.VARCHAR);
        }
    }

    private static <E extends Enum<E>> Optional<E> optionalEnum(
            Class<E> type,
            String value) {
        return value == null ? Optional.empty() : Optional.of(Enum.valueOf(type, value));
    }

    private static void putIfPresent(Map<String, String> values, String key, String value) {
        if (value != null) {
            values.put(key, value);
        }
    }

    private Connection openImmediateWriteConnection() throws SQLException {
        Connection connection = database.openConnection();
        try {
            if (!(connection instanceof SQLiteConnection sqliteConnection)) {
                throw new SQLException("The configured database connection is not SQLite");
            }
            sqliteConnection.getConnectionConfig()
                    .setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
            connection.setAutoCommit(false);
            return connection;
        } catch (SQLException | RuntimeException exception) {
            connection.close();
            throw exception;
        }
    }

    private static boolean isBusy(SQLException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLiteException sqliteException) {
                String result = sqliteException.getResultCode().name();
                if (result.startsWith("SQLITE_BUSY") || result.startsWith("SQLITE_LOCKED")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private record CandidateKey(CandidateKind kind, UUID candidateId) {
    }

    private record SourceCore(
            LegacySignType type,
            BlockPosition position,
            String signSide,
            String headerLine,
            List<String> rawLines,
            int parserVersion) {
    }

    private record AuthorityRecord(String id, String displayName) {
    }

    private record DecisionBinding(
            UUID runId,
            CandidateKind kind,
            UUID candidateId,
            UUID sourceSignId,
            String worldName,
            String decisionFingerprint,
            int decisionRevision,
            String snapshotFingerprint,
            int snapshotRevision,
            CandidateDecision decision) {
    }
}
