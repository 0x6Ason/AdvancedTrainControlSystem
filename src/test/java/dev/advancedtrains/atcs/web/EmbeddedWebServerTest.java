package dev.advancedtrains.atcs.web;

import dev.advancedtrains.atcs.config.WebConfiguration;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddedWebServerTest {
    private static final Pattern CSRF_PATTERN = Pattern.compile("\\\"csrfToken\\\":\\\"([^\\\"]+)\\\"");

    @TempDir
    Path temporaryDirectory;

    @Test
    void requiresSessionAndCsrfAndAuditsManualSymbolDecisions() throws Exception {
        SQLiteDatabase database = new SQLiteDatabase(
                new DatabaseConfiguration(temporaryDirectory.resolve("railcontrol.db"), 1000, false),
                EmbeddedWebServerTest.class,
                Clock.systemUTC());
        database.initialize();
        UUID scanId = UUID.randomUUID();
        UUID signId = UUID.randomUUID();
        seedSign(database, scanId, signId);

        int port = freePort();
        Path tokenFile = temporaryDirectory.resolve("admin.token");
        WebConfiguration configuration = new WebConfiguration(
                true, "127.0.0.1", port, 8, 100, 1000,
                tokenFile, 5, 5, false);
        AdminDataRepository repository = new AdminDataRepository(
                database, RailwayWorldPolicy.fixed(java.util.Set.of("world", "signless")), Clock.systemUTC());

        HttpClient client = HttpClient.newHttpClient();
        URI base = URI.create("http://127.0.0.1:" + port);
        try (EmbeddedWebServer server = new EmbeddedWebServer(
                configuration, repository, Clock.systemUTC(), Logger.getAnonymousLogger())) {
            server.start();
            HttpResponse<String> unauthorized = client.send(
                    HttpRequest.newBuilder(base.resolve("/api/v1/symbols")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauthorized.statusCode());

            String token = Files.readString(tokenFile, StandardCharsets.UTF_8).strip();
            HttpResponse<String> login = client.send(
                    jsonRequest(base.resolve("/api/v1/auth/login"), "POST",
                            "{\"token\":\"" + token + "\"}", null),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, login.statusCode());
            Matcher matcher = CSRF_PATTERN.matcher(login.body());
            assertTrue(matcher.find());
            String csrf = matcher.group(1);
            String sessionCookie = login.headers().firstValue("Set-Cookie")
                    .orElseThrow().split(";", 2)[0];

            HttpResponse<String> worlds = client.send(
                    HttpRequest.newBuilder(base.resolve("/api/v1/worlds"))
                            .header("Cookie", sessionCookie).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, worlds.statusCode());
            assertTrue(worlds.body().contains("world"));
            assertTrue(worlds.body().contains("signless"));

            HttpResponse<String> missingRailWorld = client.send(
                    HttpRequest.newBuilder(base.resolve("/api/v1/network/rails"))
                            .header("Cookie", sessionCookie).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, missingRailWorld.statusCode());

            String decisionBody = """
                    {"decision":"IGNORED","assignedRole":null,"targetId":null,
                     "note":"Not part of passenger service","expectedVersion":0}
                    """;
            URI decisionUri = base.resolve("/api/v1/symbols/" + signId + "/decision");
            HttpResponse<String> missingCsrf = client.send(
                    jsonRequest(decisionUri, "PUT", decisionBody, null, sessionCookie),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, missingCsrf.statusCode());

            HttpResponse<String> saved = client.send(
                    jsonRequest(decisionUri, "PUT", decisionBody, csrf, sessionCookie),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, saved.statusCode());
            assertTrue(saved.body().contains("IGNORED"));

            HttpResponse<String> station = client.send(
                    jsonRequest(base.resolve("/api/v1/stations"), "POST",
                            "{\"id\":\"CEN\",\"displayName\":\"Central\",\"worldName\":\"world\"}",
                            csrf, sessionCookie),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, station.statusCode());

            HttpResponse<String> line = client.send(
                    jsonRequest(base.resolve("/api/v1/lines"), "POST",
                            "{\"id\":\"L1\",\"displayName\":\"Line 1\",\"colorRgb\":1476519}",
                            csrf, sessionCookie),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, line.statusCode());

            HttpResponse<String> routePlan = client.send(
                    jsonRequest(base.resolve("/api/v1/route-plans"), "POST", """
                            {"id":"L1_UP","lineId":"L1","displayName":"Line 1 Up",
                             "directionName":"Up","destinationName":"Central",
                             "circular":false,"terminalPolicy":"TURNBACK",
                             "calls":[{"stationId":"CEN","stopPolicy":"STOP","dwellSeconds":8}]}
                            """, csrf, sessionCookie),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, routePlan.statusCode());
            assertTrue(routePlan.body().contains("UNASSIGNED"));

            HttpResponse<String> gate = client.send(
                    jsonRequest(base.resolve("/api/v1/gates"), "POST", """
                            {"id":"CEN_E01","displayName":"Central Entry 01","stationId":"CEN",
                             "x":10,"y":64,"z":20,"gateMode":"ENTRY",
                             "sensorDefinitionJson":"{}","outputDefinitionJson":"{}",
                             "authorizationTimeoutSeconds":10}
                            """, csrf, sessionCookie),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, gate.statusCode());

            HttpResponse<String> zone = client.send(
                    jsonRequest(base.resolve("/api/v1/fare-zones"), "POST",
                            "{\"id\":\"URBAN\",\"displayName\":\"Urban Zone\",\"colorRgb\":8421504}",
                            csrf, sessionCookie),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, zone.statusCode());

            HttpResponse<String> fareRule = client.send(
                    jsonRequest(base.resolve("/api/v1/fare-rules"), "POST", """
                            {"id":"FLAT_BASE","displayName":"Base fare","ruleType":"FLAT",
                             "amountMinor":500,"currencyCode":"HKD","priority":0,
                             "bidirectional":true}
                            """, csrf, sessionCookie),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, fareRule.statusCode());

            HttpResponse<String> lineList = client.send(
                    HttpRequest.newBuilder(base.resolve("/api/v1/lines"))
                            .header("Cookie", sessionCookie).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, lineList.statusCode());
            assertTrue(lineList.body().contains("Line 1"));

            HttpResponse<String> auditList = client.send(
                    HttpRequest.newBuilder(base.resolve("/api/v1/audit?limit=20"))
                            .header("Cookie", sessionCookie).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, auditList.statusCode());
            assertTrue(auditList.body().contains("FARE_RULE_CREATED"));
        }

        try (Connection connection = database.openConnection();
             Statement statement = connection.createStatement()) {
            try (ResultSet decisions = statement.executeQuery(
                    "SELECT decision, version FROM infrastructure_symbol_decision")) {
                assertTrue(decisions.next());
                assertEquals("IGNORED", decisions.getString(1));
                assertEquals(1, decisions.getInt(2));
            }
            try (ResultSet audits = statement.executeQuery(
                    "SELECT COUNT(*) FROM audit_record")) {
                assertTrue(audits.next());
                assertEquals(7, audits.getInt(1));
            }
        }
    }

    private static HttpRequest jsonRequest(URI uri, String method, String body, String csrf) {
        return jsonRequest(uri, method, body, csrf, null);
    }

    private static HttpRequest jsonRequest(
            URI uri,
            String method,
            String body,
            String csrf,
            String cookie) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (csrf != null) {
            builder.header("X-ATCS-CSRF", csrf);
        }
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return builder.build();
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void seedSign(SQLiteDatabase database, UUID scanId, UUID signId) throws Exception {
        try (Connection connection = database.openConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO legacy_scan_run(
                        id, started_at, completed_at, status,
                        chunks_scanned, chunks_skipped, signs_found, error_message
                    ) VALUES ('%s', '2026-08-05T00:00:00Z', '2026-08-05T00:00:01Z',
                              'COMPLETED', 1, 0, 1, NULL)
                    """.formatted(scanId));
            statement.executeUpdate("""
                    INSERT INTO legacy_sign_inventory(
                        id, world_name, chunk_x, chunk_z,
                        block_x, block_y, block_z, sign_side,
                        header_line, action_type,
                        raw_line_1, raw_line_2, raw_line_3, raw_line_4,
                        parser_version, review_status, present,
                        first_seen_at, last_seen_at, last_scan_id
                    ) VALUES (
                        '%s', 'world', 0, 0, 2, 64, 3, 'FRONT',
                        '[+train]', 'STATION', '[+train]', 'station', '5', 'continue',
                        1, 'UNREVIEWED', 1,
                        '2026-08-05T00:00:00Z', '2026-08-05T00:00:01Z', '%s'
                    )
                    """.formatted(signId, scanId));
        }
    }
}
