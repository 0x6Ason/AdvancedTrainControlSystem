package dev.advancedtrains.atcs.web;

import dev.advancedtrains.atcs.config.WebConfiguration;
import dev.advancedtrains.atcs.infrastructure.review.InfrastructureCandidateReviewService;
import dev.advancedtrains.atcs.persistence.DatabaseConfiguration;
import dev.advancedtrains.atcs.persistence.SQLiteDatabase;
import dev.advancedtrains.atcs.world.RailwayWorldPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddedWebServerRailScopeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void defaultsNetworkScopeAndRejectsInvalidScope() throws Exception {
        SQLiteDatabase database = new SQLiteDatabase(
                new DatabaseConfiguration(temporaryDirectory.resolve("railcontrol.db"), 1000, false),
                EmbeddedWebServerRailScopeTest.class,
                Clock.systemUTC());
        database.initialize();
        RailwayWorldPolicy worldPolicy = RailwayWorldPolicy.fixed(Set.of("world"));
        int port = freePort();
        Path tokenFile = temporaryDirectory.resolve("admin.token");
        WebConfiguration configuration = new WebConfiguration(
                true, "127.0.0.1", port, 8, 100, 100,
                tokenFile, 5, 5, false);
        AdminDataRepository repository = new AdminDataRepository(
                database, worldPolicy, Clock.systemUTC());
        InfrastructureCandidateReviewService reviewService =
                new InfrastructureCandidateReviewService(
                        database, worldPolicy, Clock.systemUTC());
        HttpClient client = HttpClient.newHttpClient();
        URI base = URI.create("http://127.0.0.1:" + port);

        try (EmbeddedWebServer server = new EmbeddedWebServer(
                configuration,
                repository,
                reviewService,
                Clock.systemUTC(),
                Logger.getAnonymousLogger())) {
            server.start();
            String token = Files.readString(tokenFile, StandardCharsets.UTF_8).strip();
            HttpResponse<String> login = client.send(
                    HttpRequest.newBuilder(base.resolve("/api/v1/auth/login"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"token\":\"" + token + "\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, login.statusCode());
            String cookie = login.headers().firstValue("Set-Cookie")
                    .orElseThrow().split(";", 2)[0];

            HttpResponse<String> defaultScope = get(
                    client, base.resolve("/api/v1/network/rails?world=world"), cookie);
            assertEquals(200, defaultScope.statusCode());
            assertTrue(defaultScope.headers().firstValue("Content-Length").isEmpty());
            assertTrue(defaultScope.body().contains("\"scope\":\"NETWORK\""));
            assertTrue(defaultScope.body().contains("\"visibleComponentCount\":0"));
            assertTrue(defaultScope.body().contains("\"selectedComponentCount\":0"));
            assertTrue(defaultScope.body().contains("\"returnedAnchoredComponentCount\":0"));

            HttpResponse<String> allEvidence = get(
                    client,
                    base.resolve("/api/v1/network/rails?world=world&scope=ALL_EVIDENCE"),
                    cookie);
            assertEquals(200, allEvidence.statusCode());
            assertTrue(allEvidence.body().contains("\"scope\":\"ALL_EVIDENCE\""));

            HttpResponse<String> invalid = get(
                    client,
                    base.resolve("/api/v1/network/rails?world=world&scope=EVERYTHING"),
                    cookie);
            assertEquals(400, invalid.statusCode());
            assertTrue(invalid.body().contains("scope must be NETWORK or ALL_EVIDENCE"));
        }
    }

    @Test
    void mapsRailGraphCapacityToExplicit413Response() throws Exception {
        SQLiteDatabase database = new SQLiteDatabase(
                new DatabaseConfiguration(temporaryDirectory.resolve("capacity.db"), 1000, false),
                EmbeddedWebServerRailScopeTest.class,
                Clock.systemUTC());
        database.initialize();
        seedTwoNodeRailRun(database);
        RailwayWorldPolicy worldPolicy = RailwayWorldPolicy.fixed(Set.of("world"));
        int port = freePort();
        Path tokenFile = temporaryDirectory.resolve("capacity-admin.token");
        WebConfiguration configuration = new WebConfiguration(
                true, "127.0.0.1", port, 8, 100, 100,
                tokenFile, 5, 5, false);
        AdminDataRepository repository = new AdminDataRepository(
                database, worldPolicy, Clock.systemUTC(), 1, 100);
        InfrastructureCandidateReviewService reviewService =
                new InfrastructureCandidateReviewService(
                        database, worldPolicy, Clock.systemUTC());
        HttpClient client = HttpClient.newHttpClient();
        URI base = URI.create("http://127.0.0.1:" + port);

        try (EmbeddedWebServer server = new EmbeddedWebServer(
                configuration,
                repository,
                reviewService,
                Clock.systemUTC(),
                Logger.getAnonymousLogger())) {
            server.start();
            String token = Files.readString(tokenFile, StandardCharsets.UTF_8).strip();
            HttpResponse<String> login = client.send(
                    HttpRequest.newBuilder(base.resolve("/api/v1/auth/login"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"token\":\"" + token + "\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            String cookie = login.headers().firstValue("Set-Cookie")
                    .orElseThrow().split(";", 2)[0];

            HttpResponse<String> response = get(
                    client, base.resolve("/api/v1/network/rails?world=world"), cookie);

            assertEquals(413, response.statusCode());
            assertTrue(response.body().contains("\"error\":\"RAIL_GRAPH_CAPACITY\""));
            assertTrue(response.body().contains("\"elementType\":\"nodes\""));
            assertTrue(response.body().contains("\"actualCount\":2"));
            assertTrue(response.body().contains("\"maximumCount\":1"));
            assertTrue(response.body().contains("run rail discovery again"));
        }
    }

    private static HttpResponse<String> get(
            HttpClient client,
            URI uri,
            String cookie) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri)
                        .header("Cookie", cookie)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void seedTwoNodeRailRun(SQLiteDatabase database) throws Exception {
        String runId = UUID.randomUUID().toString();
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement run = connection.prepareStatement("""
                    INSERT INTO rail_discovery_run(
                        id, started_at, completed_at, status,
                        seeds_considered, seeds_resolved,
                        nodes_discovered, edges_discovered, unloaded_boundaries,
                        node_limit_reached, error_message
                    ) VALUES (?, '2026-08-10T00:00:00Z', '2026-08-10T00:00:01Z',
                              'COMPLETED', 0, 0, 2, 0, 0, 0, NULL)
                    """)) {
                run.setString(1, runId);
                run.executeUpdate();
            }
            try (PreparedStatement node = connection.prepareStatement("""
                    INSERT INTO rail_candidate_node(
                        id, discovery_run_id, world_name,
                        block_x, block_y, block_z, material_name, rail_shape, created_at
                    ) VALUES (?, ?, 'world', ?, 64, 0, 'RAIL', 'EAST_WEST',
                              '2026-08-10T00:00:01Z')
                    """)) {
                node.setString(1, "capacity-0");
                node.setString(2, runId);
                node.setInt(3, 0);
                node.executeUpdate();
                node.setString(1, "capacity-1");
                node.setInt(3, 1);
                node.executeUpdate();
            }
            connection.commit();
        }
    }
}
