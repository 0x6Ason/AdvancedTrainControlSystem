package dev.advancedtrains.atcs.web;

import dev.advancedtrains.atcs.persistence.DatabaseConfiguration;
import dev.advancedtrains.atcs.persistence.SQLiteDatabase;
import dev.advancedtrains.atcs.world.RailwayWorldPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminDataRepositoryRailGraphTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void networkScopeClassifiesCompleteExactWorldGraphBeforeTruncation() throws Exception {
        SQLiteDatabase database = createDatabase();
        seedRailGraph(database);
        AdminDataRepository repository = new AdminDataRepository(
                database,
                RailwayWorldPolicy.fixed(Set.of("world", "other")),
                Clock.systemUTC());

        AdminDataRepository.RailGraph graph = repository.loadRailGraph(
                "world", RailGraphScope.NETWORK, 1000);

        assertEquals(RailGraphScope.NETWORK, graph.scope());
        assertEquals("world", graph.worldName());
        assertEquals(204, graph.sourceNodeCount());
        assertEquals(201, graph.sourceEdgeCount());
        assertEquals(3, graph.sourceComponentCount());
        assertEquals(2, graph.visibleComponentCount());
        assertEquals(2, graph.selectedComponentCount());
        assertEquals(1, graph.anchoredComponentCount());
        assertEquals(1, graph.returnedAnchoredComponentCount());
        assertEquals(202, graph.nodes().size());
        assertEquals(200, graph.edges().size());
        assertFalse(graph.truncated());
        assertFalse(hasX(graph, 0));
        assertFalse(hasX(graph, 1));
        assertTrue(hasX(graph, 10));
        assertTrue(hasX(graph, 299));
        assertArrayEquals(new int[] {0, 1}, graph.edges().get(0));
        assertEdgesReferenceReturnedNodes(graph);

        AdminDataRepository.RailGraph truncated = repository.loadRailGraph(
                "world", RailGraphScope.NETWORK, 2);
        assertTrue(truncated.truncated());
        assertEquals(2, truncated.nodes().size());
        assertEquals(1, truncated.edges().size());
        assertArrayEquals(new int[] {0, 1}, truncated.edges().get(0));
        assertEquals(1, truncated.visibleComponentCount());
        assertEquals(2, truncated.selectedComponentCount());
        assertEquals(1, truncated.returnedAnchoredComponentCount());
        assertEquals(204, truncated.sourceNodeCount());
        assertTrue(hasX(truncated, 10));
        assertTrue(hasX(truncated, 11));
        assertFalse(hasX(truncated, 100));
    }

    @Test
    void allEvidenceReturnsEveryNodeAndEdgeFromOnlyTheRequestedWorld() throws Exception {
        SQLiteDatabase database = createDatabase();
        seedRailGraph(database);
        AdminDataRepository repository = new AdminDataRepository(
                database,
                RailwayWorldPolicy.fixed(Set.of("world", "other")),
                Clock.systemUTC());

        AdminDataRepository.RailGraph graph = repository.loadRailGraph(
                "world", RailGraphScope.ALL_EVIDENCE, 1000);

        assertEquals(RailGraphScope.ALL_EVIDENCE, graph.scope());
        assertEquals(204, graph.nodes().size());
        assertEquals(201, graph.edges().size());
        assertEquals(3, graph.visibleComponentCount());
        assertEquals(3, graph.selectedComponentCount());
        assertEquals(3, graph.sourceComponentCount());
        assertEquals(1, graph.anchoredComponentCount());
        assertEquals(1, graph.returnedAnchoredComponentCount());
        assertTrue(hasX(graph, 0));
        assertTrue(hasX(graph, 1));
        assertEdgesReferenceReturnedNodes(graph);

        AdminDataRepository.RailGraph otherWorld = repository.loadRailGraph(
                "other", RailGraphScope.ALL_EVIDENCE, 1000);
        assertEquals("other", otherWorld.worldName());
        assertEquals(1, otherWorld.sourceNodeCount());
        assertEquals(0, otherWorld.sourceEdgeCount());
        assertEquals(1, otherWorld.nodes().size());
        assertEquals(1, otherWorld.sourceComponentCount());
    }

    @Test
    void cacheCoalescesScopesAndInvalidatesForWorldSignsAndNewRuns() throws Exception {
        SQLiteDatabase database = createDatabase();
        seedRailGraph(database);
        AdminDataRepository repository = new AdminDataRepository(
                database,
                RailwayWorldPolicy.fixed(Set.of("world", "other")),
                Clock.systemUTC());
        int requestCount = 8;
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        try {
            List<Future<AdminDataRepository.RailGraph>> futures = new java.util.ArrayList<>();
            for (int index = 0; index < requestCount; index++) {
                RailGraphScope scope = index % 2 == 0
                        ? RailGraphScope.NETWORK
                        : RailGraphScope.ALL_EVIDENCE;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return repository.loadRailGraph("world", scope, 1000);
                }));
            }
            ready.await();
            start.countDown();
            AdminDataRepository.RailGraph firstNetwork = null;
            AdminDataRepository.RailGraph firstAllEvidence = null;
            for (int index = 0; index < futures.size(); index++) {
                AdminDataRepository.RailGraph graph = futures.get(index).get();
                assertEquals(204, graph.sourceNodeCount());
                if (index % 2 == 0) {
                    if (firstNetwork == null) {
                        firstNetwork = graph;
                    } else {
                        assertSame(firstNetwork, graph);
                    }
                } else if (firstAllEvidence == null) {
                    firstAllEvidence = graph;
                } else {
                    assertSame(firstAllEvidence, graph);
                }
            }
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, repository.railGraphMaterializationCount());
        assertEquals(2, repository.railGraphResponseRenderCount());
        assertEquals(1, repository.railGraphCacheEntryCount());

        updateReviewNote(database, "world", "Review metadata is not an anchor change");
        repository.loadRailGraph("world", RailGraphScope.NETWORK, 1000);
        assertEquals(1, repository.railGraphMaterializationCount());
        assertEquals(2, repository.railGraphResponseRenderCount());

        updateReviewNote(database, "other", "Unrelated world change");
        repository.loadRailGraph("world", RailGraphScope.NETWORK, 1000);
        assertEquals(1, repository.railGraphMaterializationCount());
        assertEquals(2, repository.railGraphResponseRenderCount());

        makeWorldSignPresent(database, 0);
        AdminDataRepository.RailGraph refreshed = repository.loadRailGraph(
                "world", RailGraphScope.NETWORK, 1000);
        assertEquals(2, repository.railGraphMaterializationCount());
        assertEquals(204, refreshed.nodes().size());
        assertEquals(3, refreshed.selectedComponentCount());
        assertEquals(3, refreshed.visibleComponentCount());
        assertEquals(2, refreshed.anchoredComponentCount());
        assertEquals(2, refreshed.returnedAnchoredComponentCount());
        assertEquals(3, repository.railGraphResponseRenderCount());
        assertEquals(1, repository.railGraphCacheEntryCount());

        insertNewerEmptyRailRun(database);
        AdminDataRepository.RailGraph latest = repository.loadRailGraph(
                "world", RailGraphScope.NETWORK, 1000);
        assertEquals(3, repository.railGraphMaterializationCount());
        assertEquals(4, repository.railGraphResponseRenderCount());
        assertEquals(0, latest.sourceNodeCount());
        assertEquals(1, repository.railGraphCacheEntryCount());
    }

    @Test
    void rejectsSourceGraphBeforeMaterializingBeyondHardCapacity() throws Exception {
        SQLiteDatabase database = createDatabase();
        seedRailGraph(database);
        AdminDataRepository repository = new AdminDataRepository(
                database,
                RailwayWorldPolicy.fixed(Set.of("world", "other")),
                Clock.systemUTC(),
                100,
                1000);

        AdminDataRepository.RailGraphCapacityException exception = assertThrows(
                AdminDataRepository.RailGraphCapacityException.class,
                () -> repository.loadRailGraph("world", RailGraphScope.NETWORK, 1000));

        assertEquals("world", exception.world());
        assertEquals("nodes", exception.elementType());
        assertEquals(204, exception.actualCount());
        assertEquals(100, exception.maximumCount());
        assertEquals(0, repository.railGraphMaterializationCount());
        assertEquals(0, repository.railGraphCacheEntryCount());

        assertThrows(
                AdminDataRepository.RailGraphCapacityException.class,
                () -> repository.loadRailGraph("world", RailGraphScope.ALL_EVIDENCE, 1000));
        assertEquals(0, repository.railGraphMaterializationCount());

        AdminDataRepository edgeLimitedRepository = new AdminDataRepository(
                database,
                RailwayWorldPolicy.fixed(Set.of("world", "other")),
                Clock.systemUTC(),
                1000,
                100);
        AdminDataRepository.RailGraphCapacityException edgeException = assertThrows(
                AdminDataRepository.RailGraphCapacityException.class,
                () -> edgeLimitedRepository.loadRailGraph(
                        "world", RailGraphScope.NETWORK, 1000));
        assertEquals("edges", edgeException.elementType());
        assertEquals(201, edgeException.actualCount());
        assertEquals(100, edgeException.maximumCount());
        assertTrue(edgeException.getMessage().contains("Regenerate the rail discovery inventory"));
    }

    @Test
    void interruptedBuildersAreRemovedAndCanBeRetried() throws Exception {
        SQLiteDatabase database = createDatabase();
        seedRailGraph(database);
        AdminDataRepository sourceInterruptedRepository = new AdminDataRepository(
                database,
                RailwayWorldPolicy.fixed(Set.of("world", "other")),
                Clock.systemUTC());

        Thread.currentThread().interrupt();
        try {
            assertThrows(
                    IllegalStateException.class,
                    () -> sourceInterruptedRepository.loadRailGraph(
                            "world", RailGraphScope.NETWORK, 1000));
        } finally {
            Thread.interrupted();
        }
        AdminDataRepository.RailGraph recoveredSource = sourceInterruptedRepository.loadRailGraph(
                "world", RailGraphScope.NETWORK, 1000);
        assertEquals(202, recoveredSource.nodes().size());
        assertEquals(1, sourceInterruptedRepository.railGraphMaterializationCount());

        Thread.currentThread().interrupt();
        try {
            assertThrows(
                    IllegalStateException.class,
                    () -> sourceInterruptedRepository.loadRailGraph(
                            "world", RailGraphScope.ALL_EVIDENCE, 1000));
        } finally {
            Thread.interrupted();
        }
        AdminDataRepository.RailGraph recoveredResponse = sourceInterruptedRepository.loadRailGraph(
                "world", RailGraphScope.ALL_EVIDENCE, 1000);
        assertEquals(204, recoveredResponse.nodes().size());
        assertEquals(2, sourceInterruptedRepository.railGraphResponseRenderCount());
    }

    private SQLiteDatabase createDatabase() throws Exception {
        SQLiteDatabase database = new SQLiteDatabase(
                new DatabaseConfiguration(temporaryDirectory.resolve("railcontrol.db"), 1000, false),
                AdminDataRepositoryRailGraphTest.class,
                Clock.systemUTC());
        database.initialize();
        return database;
    }

    private static void seedRailGraph(SQLiteDatabase database) throws Exception {
        String runId = UUID.randomUUID().toString();
        String scanId = UUID.randomUUID().toString();
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement run = connection.prepareStatement("""
                    INSERT INTO rail_discovery_run(
                        id, started_at, completed_at, status,
                        seeds_considered, seeds_resolved,
                        nodes_discovered, edges_discovered, unloaded_boundaries,
                        node_limit_reached, error_message
                    ) VALUES (?, '2026-08-10T00:00:00Z', '2026-08-10T00:00:01Z',
                              'COMPLETED', 0, 0, 205, 201, 0, 0, NULL)
                    """)) {
                run.setString(1, runId);
                run.executeUpdate();
            }
            try (PreparedStatement scan = connection.prepareStatement("""
                    INSERT INTO legacy_scan_run(
                        id, started_at, completed_at, status,
                        chunks_scanned, chunks_skipped, signs_found, error_message
                    ) VALUES (?, '2026-08-10T00:00:00Z', '2026-08-10T00:00:01Z',
                              'COMPLETED', 2, 0, 3, NULL)
                    """)) {
                scan.setString(1, scanId);
                scan.executeUpdate();
            }

            try (PreparedStatement node = connection.prepareStatement("""
                    INSERT INTO rail_candidate_node(
                        id, discovery_run_id, world_name,
                        block_x, block_y, block_z, material_name, rail_shape, created_at
                    ) VALUES (?, ?, ?, ?, 64, 0, 'RAIL', 'EAST_WEST',
                              '2026-08-10T00:00:01Z')
                    """)) {
                insertNode(node, "u000", runId, "world", 0);
                insertNode(node, "u001", runId, "world", 1);
                insertNode(node, "a000", runId, "world", 10);
                insertNode(node, "a001", runId, "world", 11);
                for (int index = 0; index < 200; index++) {
                    insertNode(node, "l%03d".formatted(index), runId, "world", 100 + index);
                }
                insertNode(node, "o000", runId, "other", 0);
            }
            try (PreparedStatement edge = connection.prepareStatement("""
                    INSERT INTO rail_candidate_edge(
                        discovery_run_id, first_node_id, second_node_id
                    ) VALUES (?, ?, ?)
                    """)) {
                insertEdge(edge, runId, "u000", "u001");
                insertEdge(edge, runId, "a000", "a001");
                for (int index = 1; index < 200; index++) {
                    insertEdge(
                            edge,
                            runId,
                            "l%03d".formatted(index - 1),
                            "l%03d".formatted(index));
                }
            }
            try (PreparedStatement sign = connection.prepareStatement("""
                    INSERT INTO legacy_sign_inventory(
                        id, world_name, chunk_x, chunk_z,
                        block_x, block_y, block_z, sign_side,
                        header_line, action_type,
                        raw_line_1, raw_line_2, raw_line_3, raw_line_4,
                        parser_version, review_status, present,
                        first_seen_at, last_seen_at, last_scan_id
                    ) VALUES (?, ?, 0, 0, ?, 64, 0, 'FRONT',
                              '[+train]', 'STATION', '[+train]', 'station', '', '',
                              1, 'UNREVIEWED', ?,
                              '2026-08-10T00:00:00Z', '2026-08-10T00:00:01Z', ?)
                    """)) {
                insertSign(sign, UUID.randomUUID().toString(), "world", 10, true, scanId);
                insertSign(sign, UUID.randomUUID().toString(), "world", 0, false, scanId);
                insertSign(sign, UUID.randomUUID().toString(), "other", 0, true, scanId);
            }
            connection.commit();
        }
    }

    private static void insertNode(
            PreparedStatement statement,
            String id,
            String runId,
            String world,
            int x) throws Exception {
        statement.setString(1, id);
        statement.setString(2, runId);
        statement.setString(3, world);
        statement.setInt(4, x);
        statement.executeUpdate();
    }

    private static void insertEdge(
            PreparedStatement statement,
            String runId,
            String firstNodeId,
            String secondNodeId) throws Exception {
        statement.setString(1, runId);
        statement.setString(2, firstNodeId);
        statement.setString(3, secondNodeId);
        statement.executeUpdate();
    }

    private static void insertSign(
            PreparedStatement statement,
            String id,
            String world,
            int x,
            boolean present,
            String scanId) throws Exception {
        statement.setString(1, id);
        statement.setString(2, world);
        statement.setInt(3, x);
        statement.setInt(4, present ? 1 : 0);
        statement.setString(5, scanId);
        statement.executeUpdate();
    }

    private static void updateReviewNote(
            SQLiteDatabase database,
            String world,
            String note) throws Exception {
        try (Connection connection = database.openConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE legacy_sign_inventory
                     SET review_note = ?
                     WHERE world_name = ? AND present = 1
                     """)) {
            update.setString(1, note);
            update.setString(2, world);
            update.executeUpdate();
        }
    }

    private static void makeWorldSignPresent(SQLiteDatabase database, int x) throws Exception {
        try (Connection connection = database.openConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE legacy_sign_inventory
                     SET present = 1
                     WHERE world_name = 'world' AND block_x = ?
                     """)) {
            update.setInt(1, x);
            assertEquals(1, update.executeUpdate());
        }
    }

    private static void insertNewerEmptyRailRun(SQLiteDatabase database) throws Exception {
        try (Connection connection = database.openConnection();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO rail_discovery_run(
                         id, started_at, completed_at, status,
                         seeds_considered, seeds_resolved,
                         nodes_discovered, edges_discovered, unloaded_boundaries,
                         node_limit_reached, error_message
                     ) VALUES (?, '2026-08-10T00:00:01Z', '2026-08-10T00:00:02Z',
                               'COMPLETED', 0, 0, 0, 0, 0, 0, NULL)
                     """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.executeUpdate();
        }
    }

    private static boolean hasX(AdminDataRepository.RailGraph graph, int x) {
        return graph.nodes().stream().anyMatch(node -> node[0] == x);
    }

    private static void assertEdgesReferenceReturnedNodes(AdminDataRepository.RailGraph graph) {
        int nodeCount = graph.nodes().size();
        for (int[] edge : graph.edges()) {
            assertEquals(2, edge.length);
            assertTrue(edge[0] >= 0 && edge[0] < nodeCount, Arrays.toString(edge));
            assertTrue(edge[1] >= 0 && edge[1] < nodeCount, Arrays.toString(edge));
        }
    }
}
