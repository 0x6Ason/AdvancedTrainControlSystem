package dev.advancedtrains.atcs.legacy;

import dev.advancedtrains.atcs.config.LegacyReviewConfiguration;
import dev.advancedtrains.atcs.domain.BlockPosition;
import dev.advancedtrains.atcs.persistence.SQLiteDatabase;
import dev.advancedtrains.atcs.world.RailwayWorldPolicy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Maintains a configurable, read-only-to-the-world review queue for legacy signs.
 */
public final class LegacyReviewRepository {
    private static final Pattern ID_PREFIX = Pattern.compile("[0-9a-f]{8,32}");

    private final SQLiteDatabase database;
    private final LegacyReviewConfiguration configuration;
    private final RailwayWorldPolicy worldPolicy;
    private final LegacySignIssueDetector issueDetector;
    private final Clock clock;

    public LegacyReviewRepository(
            SQLiteDatabase database,
            LegacyReviewConfiguration configuration,
            RailwayWorldPolicy worldPolicy,
            Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.worldPolicy = Objects.requireNonNull(worldPolicy, "worldPolicy");
        this.issueDetector = new LegacySignIssueDetector(configuration);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public LegacyReviewSummary refresh() throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                replaceIssues(connection);
                LegacyReviewSummary summary = loadSummary(connection);
                connection.commit();
                return summary;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public LegacyReviewSummary loadSummary() throws SQLException {
        try (Connection connection = database.openConnection()) {
            return loadSummary(connection);
        }
    }

    public LegacyReviewPage listIssuePage(int page) throws SQLException {
        if (page <= 0) {
            throw new IllegalArgumentException("Review page number must be positive");
        }
        try (Connection connection = database.openConnection()) {
            List<String> activeIssueIds = loadActiveIssueIds(connection);
            int totalItems = activeIssueIds.size();
            int offset = (page - 1) * configuration.pageSize();
            List<LegacyReviewItem> items = new ArrayList<>();
            int end = Math.min(offset + configuration.pageSize(), totalItems);
            if (offset < end) {
                for (String id : activeIssueIds.subList(offset, end)) {
                    items.add(loadItem(connection, id).orElseThrow());
                }
            }
            return new LegacyReviewPage(page, configuration.pageSize(), totalItems, items);
        }
    }

    public Optional<LegacyReviewItem> findByIdPrefix(String input) throws SQLException {
        String prefix = normalizeIdPrefix(input);
        try (Connection connection = database.openConnection()) {
            Optional<String> id = resolveIdPrefix(connection, prefix);
            return id.isEmpty() ? Optional.empty() : loadItem(connection, id.orElseThrow());
        }
    }

    public Optional<LegacyReviewItem> updateStatus(
            String input,
            LegacyReviewStatus newStatus,
            String actorType,
            String actorId,
            Optional<String> note) throws SQLException {
        Objects.requireNonNull(newStatus, "newStatus");
        Objects.requireNonNull(actorType, "actorType");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(note, "note");
        if (newStatus == LegacyReviewStatus.ARCHIVED) {
            throw new IllegalArgumentException("ARCHIVED is not an operator review state");
        }
        String normalizedActorType = requireShortText(actorType, "Actor type", 32).toUpperCase(Locale.ROOT);
        String normalizedActorId = requireShortText(actorId, "Actor ID", 128);
        Optional<String> normalizedNote = note.map(String::strip).filter(value -> !value.isEmpty());
        if (normalizedNote.map(String::length).orElse(0) > 500) {
            throw new IllegalArgumentException("Review notes cannot exceed 500 characters");
        }
        String prefix = normalizeIdPrefix(input);

        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<String> resolvedId = resolveIdPrefix(connection, prefix);
                if (resolvedId.isEmpty()) {
                    connection.rollback();
                    return Optional.empty();
                }
                String id = resolvedId.orElseThrow();
                LegacyReviewItem before = loadItem(connection, id).orElseThrow();
                if (before.status() == LegacyReviewStatus.ARCHIVED) {
                    throw new IllegalArgumentException(
                            "Archived legacy records cannot be reviewed");
                }

                Instant now = Instant.now(clock);
                String reviewer = normalizedActorType + ":" + normalizedActorId;
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE legacy_sign_inventory
                        SET review_status = ?, reviewed_by = ?, reviewed_at = ?, review_note = ?
                        WHERE id = ?
                        """)) {
                    update.setString(1, newStatus.name());
                    if (newStatus == LegacyReviewStatus.UNREVIEWED) {
                        update.setNull(2, java.sql.Types.VARCHAR);
                        update.setNull(3, java.sql.Types.VARCHAR);
                        update.setNull(4, java.sql.Types.VARCHAR);
                    } else {
                        update.setString(2, reviewer);
                        update.setString(3, now.toString());
                        if (normalizedNote.isPresent()) {
                            update.setString(4, normalizedNote.orElseThrow());
                        } else {
                            update.setNull(4, java.sql.Types.VARCHAR);
                        }
                    }
                    update.setString(5, id);
                    update.executeUpdate();
                }

                insertAuditRecord(
                        connection,
                        id,
                        normalizedActorType,
                        normalizedActorId,
                        before.status(),
                        before.reviewNote(),
                        newStatus,
                        normalizedNote,
                        now);
                LegacyReviewItem after = loadItem(connection, id).orElseThrow();
                connection.commit();
                return Optional.of(after);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public int pageSize() {
        return configuration.pageSize();
    }

    private void replaceIssues(Connection connection) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM legacy_sign_issue")) {
            delete.executeUpdate();
        }
        List<LegacyReviewSubject> subjects = loadPresentSubjects(connection);
        String detectedAt = Instant.now(clock).toString();
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO legacy_sign_issue(sign_id, issue_code, severity, details, detected_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            for (LegacyReviewSubject subject : subjects) {
                for (LegacyReviewIssue issue : issueDetector.detect(subject)) {
                    insert.setString(1, subject.signId().toString());
                    insert.setString(2, issue.code().name());
                    insert.setString(3, issue.severity().name());
                    insert.setString(4, issue.details());
                    insert.setString(5, detectedAt);
                    insert.addBatch();
                }
            }
            insert.executeBatch();
        }
    }

    private List<LegacyReviewSubject> loadPresentSubjects(Connection connection) throws SQLException {
        List<LegacyReviewSubject> subjects = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT s.id, s.world_name, s.block_x, s.block_y, s.block_z, s.sign_side,
                       s.action_type, s.raw_line_1, s.raw_line_2, s.raw_line_3, s.raw_line_4,
                       (SELECT a.attribute_value FROM legacy_sign_attribute a
                        WHERE a.sign_id = s.id AND a.attribute_key = 'action') AS action,
                       (SELECT a.attribute_value FROM legacy_sign_attribute a
                        WHERE a.sign_id = s.id AND a.attribute_key = 'spawn_pattern') AS spawn_pattern,
                       EXISTS (SELECT 1 FROM legacy_sign_attribute a
                               WHERE a.sign_id = s.id AND a.attribute_key = 'regular_minecart_count')
                           AS regular_count_present,
                       (SELECT a.attribute_value FROM legacy_sign_attribute a
                        WHERE a.sign_id = s.id AND a.attribute_key = 'launch_force') AS launch_force
                FROM legacy_sign_inventory s
                WHERE s.present = 1
                ORDER BY s.world_name, s.block_x, s.block_y, s.block_z, s.sign_side
                """);
             ResultSet result = query.executeQuery()) {
            while (result.next()) {
                if (worldPolicy.isActive(result.getString("world_name"))) {
                    subjects.add(readSubject(result));
                }
            }
        }
        return List.copyOf(subjects);
    }

    private LegacyReviewSubject readSubject(ResultSet result) throws SQLException {
        return new LegacyReviewSubject(
                UUID.fromString(result.getString("id")),
                new BlockPosition(
                        result.getString("world_name"),
                        result.getInt("block_x"),
                        result.getInt("block_y"),
                        result.getInt("block_z")),
                result.getString("sign_side"),
                LegacySignType.valueOf(result.getString("action_type")),
                Objects.requireNonNullElse(result.getString("action"), ""),
                Optional.ofNullable(result.getString("spawn_pattern")),
                result.getBoolean("regular_count_present"),
                Optional.ofNullable(result.getString("launch_force")),
                List.of(
                        result.getString("raw_line_1"),
                        result.getString("raw_line_2"),
                        result.getString("raw_line_3"),
                        result.getString("raw_line_4")));
    }

    private LegacyReviewSummary loadSummary(Connection connection) throws SQLException {
        Map<LegacyReviewStatus, Integer> statuses = new EnumMap<>(LegacyReviewStatus.class);
        int presentSigns = 0;
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT world_name, review_status
                FROM legacy_sign_inventory
                WHERE present = 1
                """);
             ResultSet result = query.executeQuery()) {
            while (result.next()) {
                if (!worldPolicy.isActive(result.getString(1))) {
                    continue;
                }
                LegacyReviewStatus status = LegacyReviewStatus.valueOf(result.getString(2));
                statuses.merge(status, 1, Integer::sum);
                presentSigns++;
            }
        }

        Map<LegacyIssueCode, Integer> issueCounts = new EnumMap<>(LegacyIssueCode.class);
        int openIssues = 0;
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT s.world_name, i.issue_code
                FROM legacy_sign_issue i
                JOIN legacy_sign_inventory s ON s.id = i.sign_id
                WHERE s.present = 1
                  AND s.review_status IN ('UNREVIEWED', 'NEEDS_CORRECTION')
                """);
             ResultSet result = query.executeQuery()) {
            while (result.next()) {
                if (!worldPolicy.isActive(result.getString(1))) {
                    continue;
                }
                LegacyIssueCode code = LegacyIssueCode.valueOf(result.getString(2));
                issueCounts.merge(code, 1, Integer::sum);
                openIssues++;
            }
        }
        return new LegacyReviewSummary(
                presentSigns, statuses, countIssueSigns(connection), openIssues, issueCounts);
    }

    private int countIssueSigns(Connection connection) throws SQLException {
        return loadActiveIssueIds(connection).size();
    }

    private List<String> loadActiveIssueIds(Connection connection) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT DISTINCT s.id, s.world_name
                FROM legacy_sign_inventory s
                JOIN legacy_sign_issue i ON i.sign_id = s.id
                WHERE s.present = 1
                  AND s.review_status IN ('UNREVIEWED', 'NEEDS_CORRECTION')
                ORDER BY
                    CASE
                        WHEN EXISTS (
                            SELECT 1 FROM legacy_sign_issue e
                            WHERE e.sign_id = s.id AND e.severity = 'ERROR'
                        ) THEN 0
                        WHEN EXISTS (
                            SELECT 1 FROM legacy_sign_issue w
                            WHERE w.sign_id = s.id AND w.severity = 'WARNING'
                        ) THEN 1
                        ELSE 2
                    END,
                    s.world_name, s.block_x, s.block_y, s.block_z, s.sign_side
                """);
             ResultSet result = query.executeQuery()) {
            while (result.next()) {
                if (worldPolicy.isActive(result.getString(2))) {
                    ids.add(result.getString(1));
                }
            }
        }
        return List.copyOf(ids);
    }

    private Optional<LegacyReviewItem> loadItem(Connection connection, String id) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT s.id, s.world_name, s.block_x, s.block_y, s.block_z, s.sign_side,
                       s.action_type, s.raw_line_1, s.raw_line_2, s.raw_line_3, s.raw_line_4,
                       s.review_status, s.reviewed_by, s.reviewed_at, s.review_note,
                       (SELECT a.attribute_value FROM legacy_sign_attribute a
                        WHERE a.sign_id = s.id AND a.attribute_key = 'action') AS action,
                       (SELECT a.attribute_value FROM legacy_sign_attribute a
                        WHERE a.sign_id = s.id AND a.attribute_key = 'spawn_pattern') AS spawn_pattern,
                       EXISTS (SELECT 1 FROM legacy_sign_attribute a
                               WHERE a.sign_id = s.id AND a.attribute_key = 'regular_minecart_count')
                           AS regular_count_present,
                       (SELECT a.attribute_value FROM legacy_sign_attribute a
                        WHERE a.sign_id = s.id AND a.attribute_key = 'launch_force') AS launch_force
                FROM legacy_sign_inventory s
                WHERE s.id = ?
                """)) {
            query.setString(1, id);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                if (!worldPolicy.isActive(result.getString("world_name"))) {
                    return Optional.empty();
                }
                LegacyReviewSubject subject = readSubject(result);
                return Optional.of(new LegacyReviewItem(
                        subject,
                        LegacyReviewStatus.valueOf(result.getString("review_status")),
                        Optional.ofNullable(result.getString("reviewed_by")),
                        parseInstant(result.getString("reviewed_at")),
                        Optional.ofNullable(result.getString("review_note")),
                        loadIssues(connection, id)));
            }
        }
    }

    private List<LegacyReviewIssue> loadIssues(Connection connection, String id) throws SQLException {
        List<LegacyReviewIssue> issues = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT issue_code, severity, details
                FROM legacy_sign_issue
                WHERE sign_id = ?
                ORDER BY CASE severity WHEN 'ERROR' THEN 0 WHEN 'WARNING' THEN 1 ELSE 2 END,
                         issue_code
                """)) {
            query.setString(1, id);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    issues.add(new LegacyReviewIssue(
                            LegacyIssueCode.valueOf(result.getString(1)),
                            LegacyIssueSeverity.valueOf(result.getString(2)),
                            result.getString(3)));
                }
            }
        }
        return List.copyOf(issues);
    }

    private Optional<String> resolveIdPrefix(Connection connection, String prefix) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT id, world_name
                FROM legacy_sign_inventory
                WHERE present = 1
                  AND lower(replace(id, '-', '')) LIKE ?
                ORDER BY id
                LIMIT 2
                """)) {
            query.setString(1, prefix + "%");
            List<String> ids = new ArrayList<>();
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    if (worldPolicy.isActive(result.getString(2))) {
                        ids.add(result.getString(1));
                    }
                }
            }
            if (ids.size() > 1) {
                throw new IllegalArgumentException("The sign ID prefix is ambiguous; provide more characters");
            }
            return ids.isEmpty() ? Optional.empty() : Optional.of(ids.getFirst());
        }
    }

    private void insertAuditRecord(
            Connection connection,
            String signId,
            String actorType,
            String actorId,
            LegacyReviewStatus beforeStatus,
            Optional<String> beforeNote,
            LegacyReviewStatus afterStatus,
            Optional<String> afterNote,
            Instant now) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO audit_record(
                    id, correlation_id, actor_type, actor_id, action,
                    target_type, target_id, before_json, after_json, created_at
                ) VALUES (?, ?, ?, ?, 'LEGACY_SIGN_REVIEW_UPDATED',
                          'LEGACY_SIGN', ?, ?, ?, ?)
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, UUID.randomUUID().toString());
            insert.setString(3, actorType);
            insert.setString(4, actorId);
            insert.setString(5, signId);
            insert.setString(6, reviewJson(beforeStatus, beforeNote));
            insert.setString(7, reviewJson(afterStatus, afterNote));
            insert.setString(8, now.toString());
            insert.executeUpdate();
        }
    }

    private String reviewJson(LegacyReviewStatus status, Optional<String> note) {
        return "{\"status\":\"" + status.name() + "\",\"note\":"
                + note.map(value -> "\"" + escapeJson(value) + "\"").orElse("null") + "}";
    }

    private String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private String requireShortText(String value, String field, int maximumLength) {
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(
                    field + " must contain between 1 and " + maximumLength + " characters");
        }
        return normalized;
    }

    private Optional<Instant> parseInstant(String value) {
        return value == null ? Optional.empty() : Optional.of(Instant.parse(value));
    }

    private String normalizeIdPrefix(String input) {
        Objects.requireNonNull(input, "input");
        String normalized = input.replace("-", "").strip().toLowerCase(Locale.ROOT);
        if (!ID_PREFIX.matcher(normalized).matches()) {
            throw new IllegalArgumentException("A sign ID prefix must contain 8 to 32 hexadecimal characters");
        }
        return normalized;
    }
}
