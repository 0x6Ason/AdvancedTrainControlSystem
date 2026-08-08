package dev.advancedtrains.atcs.legacy;

import dev.advancedtrains.atcs.config.LegacyReviewConfiguration;
import dev.advancedtrains.atcs.domain.BlockPosition;
import dev.advancedtrains.atcs.persistence.DatabaseConfiguration;
import dev.advancedtrains.atcs.persistence.SQLiteDatabase;
import dev.advancedtrains.atcs.world.RailwayWorldPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyReviewRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void buildsIssueQueueOnlyForTrainCartsActiveWorlds() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-08-05T00:00:00Z"), ZoneOffset.UTC);
        SQLiteDatabase database = new SQLiteDatabase(
                new DatabaseConfiguration(temporaryDirectory.resolve("review.db"), 1000, false),
                LegacyReviewRepositoryTest.class,
                clock);
        database.initialize();
        LegacySignInventoryRepository inventory = new LegacySignInventoryRepository(database);
        LegacySignClassifier classifier = new LegacySignClassifier();

        LegacyTrainCartsSign misspelled = classifier.classify(
                new BlockPosition("world", 1, 64, 1),
                "FRONT",
                List.of("[+train]", "annnounce", "Arrived: Old", "Village St.")).orElseThrow();
        LegacyTrainCartsSign complexSpawn = classifier.classify(
                new BlockPosition("world", 2, 64, 2),
                "FRONT",
                List.of("[+train]", "spawn 4", "2m24s2m", "")).orElseThrow();
        LegacyTrainCartsSign archivedSpawn = classifier.classify(
                new BlockPosition("old_world", 3, 64, 3),
                "FRONT",
                List.of("[+train]", "spawn 9999", "12m", "")).orElseThrow();
        inventory.storeCompletedScan(new LegacyScanResult(
                UUID.randomUUID(),
                Instant.parse("2026-08-04T23:59:58Z"),
                Instant.parse("2026-08-04T23:59:59Z"),
                List.of(new LegacyScanChunk("world", 0, 0), new LegacyScanChunk("old_world", 0, 0)),
                0,
                List.of(misspelled, complexSpawn, archivedSpawn)));

        LegacyReviewConfiguration configuration = new LegacyReviewConfiguration(
                Map.of("annnounce", "announce"), true, 100, 0, 8);
        LegacyReviewRepository repository = new LegacyReviewRepository(
                database,
                configuration,
                RailwayWorldPolicy.fixed(Set.of("world")),
                clock);

        LegacyReviewSummary summary = repository.refresh();

        assertEquals(2, summary.presentSigns());
        assertEquals(2, summary.statusCount(LegacyReviewStatus.UNREVIEWED));
        assertEquals(0, summary.statusCount(LegacyReviewStatus.ARCHIVED));
        assertEquals(2, summary.issueSigns());
        assertEquals(3, summary.openIssues());
        assertEquals(1, summary.issuesByCode().get(LegacyIssueCode.ACTION_ALIAS));
        assertEquals(1, summary.issuesByCode().get(LegacyIssueCode.COMPLEX_SPAWN_PATTERN));
        assertEquals(1, summary.issuesByCode().get(LegacyIssueCode.LAUNCH_FORCE_OUT_OF_RANGE));

        LegacyReviewPage page = repository.listIssuePage(1);
        assertEquals(2, page.totalItems());
        assertEquals(2, page.items().size());
        assertEquals(misspelled.id(), page.items().getFirst().subject().signId());

        assertTrue(repository.findByIdPrefix(
                archivedSpawn.id().toString().substring(0, 8)).isEmpty());

        LegacyReviewItem approved = repository.updateStatus(
                misspelled.id().toString().substring(0, 8),
                LegacyReviewStatus.APPROVED,
                "PLAYER",
                "00000000-0000-0000-0000-000000000001",
                Optional.of("Verified against the station plan.")).orElseThrow();
        assertEquals(LegacyReviewStatus.APPROVED, approved.status());
        assertEquals("PLAYER:00000000-0000-0000-0000-000000000001", approved.reviewedBy().orElseThrow());
        assertEquals(1, repository.loadSummary().issueSigns());
        assertEquals(2, repository.loadSummary().openIssues());

        assertTrue(repository.updateStatus(
                archivedSpawn.id().toString().substring(0, 8),
                LegacyReviewStatus.APPROVED,
                "CONSOLE",
                "CONSOLE",
                Optional.empty()).isEmpty());

        try (var connection = database.openConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT before_json, after_json
                     FROM audit_record
                     WHERE action = 'LEGACY_SIGN_REVIEW_UPDATED'
                     """)) {
            assertTrue(result.next());
            assertTrue(result.getString(1).contains("UNREVIEWED"));
            assertTrue(result.getString(2).contains("APPROVED"));
        }

        LegacyTrainCartsSign corrected = classifier.classify(
                new BlockPosition("world", 1, 64, 1),
                "FRONT",
                List.of("[+train]", "announce", "Arrived: Old", "Village St.")).orElseThrow();
        inventory.storeCompletedScan(new LegacyScanResult(
                UUID.randomUUID(),
                Instant.parse("2026-08-05T00:00:01Z"),
                Instant.parse("2026-08-05T00:00:02Z"),
                List.of(new LegacyScanChunk("world", 0, 0), new LegacyScanChunk("old_world", 0, 0)),
                0,
                List.of(corrected, complexSpawn, archivedSpawn)));
        LegacyReviewSummary changedEvidence = repository.refresh();
        LegacyReviewItem invalidated = repository.findByIdPrefix(
                corrected.id().toString().substring(0, 8)).orElseThrow();
        assertEquals(LegacyReviewStatus.UNREVIEWED, invalidated.status());
        assertTrue(invalidated.reviewedBy().isEmpty());
        assertEquals(1, changedEvidence.issueSigns());

        LegacyReviewRepository unrestricted = new LegacyReviewRepository(
                database,
                configuration,
                RailwayWorldPolicy.unrestricted(),
                clock);
        LegacyReviewSummary restored = unrestricted.refresh();
        assertEquals(3, restored.statusCount(LegacyReviewStatus.UNREVIEWED));
        assertEquals(0, restored.statusCount(LegacyReviewStatus.ARCHIVED));
    }
}
