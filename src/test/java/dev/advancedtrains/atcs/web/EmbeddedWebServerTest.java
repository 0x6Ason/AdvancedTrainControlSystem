package dev.advancedtrains.atcs.web;

import dev.advancedtrains.atcs.config.WebConfiguration;
import dev.advancedtrains.atcs.config.InfrastructureDiscoveryConfiguration;
import dev.advancedtrains.atcs.discovery.InfrastructureCandidateRepository;
import dev.advancedtrains.atcs.discovery.InfrastructureDiscoverySummary;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        InfrastructureCandidateReviewService candidateReviewService =
                new InfrastructureCandidateReviewService(
                        database,
                        RailwayWorldPolicy.fixed(java.util.Set.of("world", "signless")),
                        Clock.systemUTC());

        HttpClient client = HttpClient.newHttpClient();
        URI base = URI.create("http://127.0.0.1:" + port);
        try (EmbeddedWebServer server = new EmbeddedWebServer(
                configuration,
                repository,
                candidateReviewService,
                Clock.systemUTC(),
                Logger.getAnonymousLogger())) {
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

    @Test
    void servesFilteredCandidateReviewsAndEnforcesDecisionConcurrency() throws Exception {
        SQLiteDatabase database = new SQLiteDatabase(
                new DatabaseConfiguration(temporaryDirectory.resolve("candidate-review.db"), 1000, false),
                EmbeddedWebServerTest.class,
                Clock.systemUTC());
        database.initialize();
        UUID scanId = UUID.randomUUID();
        UUID departureSignId = UUID.randomUUID();
        UUID motionSignId = UUID.randomUUID();
        UUID reversingSignId = UUID.randomUUID();
        seedCandidateSigns(database, scanId, departureSignId, motionSignId, reversingSignId);

        RailwayWorldPolicy worldPolicy = RailwayWorldPolicy.fixed(Set.of("world", "signless"));
        InfrastructureCandidateRepository discoveryRepository = new InfrastructureCandidateRepository(
                database, worldPolicy, discoveryConfiguration(), Clock.systemUTC());
        InfrastructureDiscoverySummary summary = discoveryRepository.discover();
        UUID runId = summary.runId().orElseThrow();
        UUID departureId = candidateId(database, "departure_candidate", departureSignId);
        UUID motionId = candidateId(database, "track_control_candidate", motionSignId);
        UUID reversingId = candidateId(database, "track_control_candidate", reversingSignId);

        int port = freePort();
        Path tokenFile = temporaryDirectory.resolve("candidate-admin.token");
        WebConfiguration configuration = new WebConfiguration(
                true, "127.0.0.1", port, 8, 2, 1000,
                tokenFile, 5, 5, false);
        AdminDataRepository repository = new AdminDataRepository(
                database, worldPolicy, Clock.systemUTC());
        InfrastructureCandidateReviewService reviewService =
                new InfrastructureCandidateReviewService(database, worldPolicy, Clock.systemUTC());
        HttpClient client = HttpClient.newHttpClient();
        URI base = URI.create("http://127.0.0.1:" + port);

        try (EmbeddedWebServer server = new EmbeddedWebServer(
                configuration,
                repository,
                reviewService,
                Clock.systemUTC(),
                Logger.getAnonymousLogger())) {
            server.start();

            HttpResponse<String> unauthorized = client.send(
                    HttpRequest.newBuilder(base.resolve("/api/v1/infrastructure-candidates"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauthorized.statusCode());

            AuthSession auth = login(client, base, tokenFile);
            HttpResponse<String> page = get(client, base.resolve(
                    "/api/v1/infrastructure-candidates?offset=1&limit=1"), auth.cookie());
            assertEquals(200, page.statusCode(), page.body());
            assertTrue(page.body().contains("\"total\":3"));
            assertTrue(page.body().contains("\"offset\":1"));
            assertTrue(page.body().contains(motionId.toString()));
            assertFalse(page.body().contains(departureId.toString()));
            assertFalse(page.body().contains(reversingId.toString()));

            HttpResponse<String> filtered = get(client, base.resolve(
                    "/api/v1/infrastructure-candidates?world=world&type=DEPARTURE"
                            + "&decision=PENDING&search=12m&offset=0&limit=2"), auth.cookie());
            assertEquals(200, filtered.statusCode());
            assertTrue(filtered.body().contains("\"total\":1"));
            assertTrue(filtered.body().contains(departureId.toString()));
            assertTrue(filtered.body().contains("12m"));

            HttpResponse<String> roleFiltered = get(client, base.resolve(
                    "/api/v1/infrastructure-candidates?role=REVERSING_CONTROL"), auth.cookie());
            assertEquals(200, roleFiltered.statusCode());
            assertTrue(roleFiltered.body().contains("\"total\":1"));
            assertTrue(roleFiltered.body().contains(reversingId.toString()));

            HttpResponse<String> detail = get(client, base.resolve(
                    "/api/v1/infrastructure-candidates/" + departureId), auth.cookie());
            assertEquals(200, detail.statusCode());
            assertTrue(detail.body().contains("[+train]"));
            assertTrue(detail.body().contains("SPAWN_SIGN"));
            assertTrue(detail.body().contains("PENDING"));

            HttpResponse<String> invalidFilter = get(client, base.resolve(
                    "/api/v1/infrastructure-candidates?role=PASSENGER_PLATFORM"), auth.cookie());
            assertEquals(400, invalidFilter.statusCode());

            HttpResponse<String> missing = get(client, base.resolve(
                    "/api/v1/infrastructure-candidates/" + UUID.randomUUID()), auth.cookie());
            assertEquals(404, missing.statusCode());

            URI decisionUri = base.resolve(
                    "/api/v1/infrastructure-candidates/" + departureId + "/decision");
            String include = """
                    {"expectedRunId":"%s","expectedDecisionVersion":0,
                     "decision":"INCLUDED","note":"Approved fixture departure",
                     "authority":{"targetId":"WEB_DEP","displayName":"Web Departure"}}
                    """.formatted(runId);
            HttpResponse<String> missingCsrf = client.send(
                    jsonRequest(decisionUri, "PUT", include, null, auth.cookie()),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, missingCsrf.statusCode());

            String invalid = """
                    {"expectedRunId":"%s","expectedDecisionVersion":0,
                     "decision":"INCLUDED","note":"Missing authority display name",
                     "authority":{"targetId":"WEB_DEP"}}
                    """.formatted(runId);
            HttpResponse<String> invalidDecision = client.send(
                    jsonRequest(decisionUri, "PUT", invalid, auth.csrf(), auth.cookie()),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, invalidDecision.statusCode());

            String wrongRun = """
                    {"expectedRunId":"%s","expectedDecisionVersion":0,
                     "decision":"INCLUDED",
                     "authority":{"targetId":"WEB_DEP","displayName":"Web Departure"}}
                    """.formatted(UUID.randomUUID());
            HttpResponse<String> runConflict = client.send(
                    jsonRequest(decisionUri, "PUT", wrongRun, auth.csrf(), auth.cookie()),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(409, runConflict.statusCode());

            URI unknownDecisionUri = base.resolve(
                    "/api/v1/infrastructure-candidates/" + UUID.randomUUID() + "/decision");
            String ignore = """
                    {"expectedRunId":"%s","expectedDecisionVersion":0,
                     "decision":"IGNORED","note":"Unknown fixture"}
                    """.formatted(runId);
            HttpResponse<String> unknownDecision = client.send(
                    jsonRequest(unknownDecisionUri, "PUT", ignore, auth.csrf(), auth.cookie()),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, unknownDecision.statusCode());

            HttpResponse<String> saved = client.send(
                    jsonRequest(decisionUri, "PUT", include, auth.csrf(), auth.cookie()),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, saved.statusCode());
            assertTrue(saved.body().contains("\"authorityChanged\":true"));
            assertTrue(saved.body().contains("INCLUDED"));
            assertTrue(saved.body().contains("WEB_DEP"));
            assertTrue(saved.body().contains("Web Departure"));

            HttpResponse<String> versionConflict = client.send(
                    jsonRequest(decisionUri, "PUT", include, auth.csrf(), auth.cookie()),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(409, versionConflict.statusCode());

            HttpResponse<String> included = get(client, base.resolve(
                    "/api/v1/infrastructure-candidates?decision=INCLUDED"), auth.cookie());
            assertEquals(200, included.statusCode());
            assertTrue(included.body().contains("\"total\":1"));
            assertTrue(included.body().contains("Web Departure"));

            String pendingWithAuthority = """
                    {"expectedRunId":"%s","expectedDecisionVersion":1,
                     "decision":"PENDING","authority":{"targetId":"WEB_DEP"}}
                    """.formatted(runId);
            HttpResponse<String> invalidClear = client.send(
                    jsonRequest(
                            decisionUri,
                            "PUT",
                            pendingWithAuthority,
                            auth.csrf(),
                            auth.cookie()),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, invalidClear.statusCode());

            String pending = """
                    {"expectedRunId":"%s","expectedDecisionVersion":1,
                     "decision":"PENDING"}
                    """.formatted(runId);
            HttpResponse<String> cleared = client.send(
                    jsonRequest(decisionUri, "PUT", pending, auth.csrf(), auth.cookie()),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, cleared.statusCode());
            assertTrue(cleared.body().contains("\"authorityChanged\":true"));
            assertTrue(cleared.body().contains("PENDING"));

            HttpResponse<String> pendingList = get(client, base.resolve(
                    "/api/v1/infrastructure-candidates?decision=PENDING"), auth.cookie());
            assertEquals(200, pendingList.statusCode());
            assertTrue(pendingList.body().contains("\"total\":3"));
        }

        try (Connection connection = database.openConnection();
             Statement statement = connection.createStatement()) {
            try (ResultSet authority = statement.executeQuery("""
                    SELECT display_name, source_discovery_run_id, source_candidate_id,
                           source_sign_id, enabled
                    FROM departure_point
                    WHERE id = 'WEB_DEP'
                    """)) {
                assertTrue(authority.next());
                assertEquals("Web Departure", authority.getString(1));
                assertEquals(runId.toString(), authority.getString(2));
                assertEquals(departureId.toString(), authority.getString(3));
                assertEquals(departureSignId.toString(), authority.getString(4));
                assertEquals(0, authority.getInt(5));
            }
            try (ResultSet decision = statement.executeQuery("""
                    SELECT decision_status, version, authority_role, target_id, note
                    FROM infrastructure_candidate_decision
                    WHERE candidate_id = '%s'
                    """.formatted(departureId))) {
                assertTrue(decision.next());
                assertEquals("PENDING", decision.getString(1));
                assertEquals(2, decision.getInt(2));
                assertNull(decision.getString(3));
                assertNull(decision.getString(4));
                assertNull(decision.getString(5));
            }
            try (ResultSet audit = statement.executeQuery(
                    "SELECT COUNT(*) FROM audit_record")) {
                assertTrue(audit.next());
                assertEquals(2, audit.getInt(1));
            }
        }
    }

    private static AuthSession login(HttpClient client, URI base, Path tokenFile) throws Exception {
        String token = Files.readString(tokenFile, StandardCharsets.UTF_8).strip();
        HttpResponse<String> login = client.send(
                jsonRequest(base.resolve("/api/v1/auth/login"), "POST",
                        "{\"token\":\"" + token + "\"}", null),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode());
        Matcher matcher = CSRF_PATTERN.matcher(login.body());
        assertTrue(matcher.find());
        String cookie = login.headers().firstValue("Set-Cookie")
                .orElseThrow()
                .split(";", 2)[0];
        return new AuthSession(cookie, matcher.group(1));
    }

    private static HttpResponse<String> get(HttpClient client, URI uri, String cookie)
            throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri).header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static InfrastructureDiscoveryConfiguration discoveryConfiguration() {
        return new InfrastructureDiscoveryConfiguration(
                new InfrastructureDiscoveryConfiguration.StationEvidence(
                        List.of("Arrived:", "Arrive:"), 16, 4),
                new InfrastructureDiscoveryConfiguration.StationClassification(
                        true, Set.of("reverse"), Set.of("continue")),
                new InfrastructureDiscoveryConfiguration.StationGrouping(48, 12),
                new InfrastructureDiscoveryConfiguration.Confidence(0.9, 0.4, 0.85, 0.7),
                new InfrastructureDiscoveryConfiguration.Rail(
                        InfrastructureDiscoveryConfiguration.Rail.Scope.SAVED_CHUNKS,
                        2,
                        16384,
                        4096,
                        500000));
    }

    private static UUID candidateId(SQLiteDatabase database, String table, UUID sourceSignId)
            throws Exception {
        String query = switch (table) {
            case "departure_candidate" ->
                    "SELECT id FROM departure_candidate WHERE source_sign_id = ?";
            case "track_control_candidate" ->
                    "SELECT id FROM track_control_candidate WHERE source_sign_id = ?";
            default -> throw new IllegalArgumentException("Unsupported candidate table");
        };
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, sourceSignId.toString());
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return UUID.fromString(result.getString(1));
            }
        }
    }

    private static void seedCandidateSigns(
            SQLiteDatabase database,
            UUID scanId,
            UUID departureSignId,
            UUID motionSignId,
            UUID reversingSignId) throws Exception {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement scan = connection.prepareStatement("""
                    INSERT INTO legacy_scan_run(
                        id, started_at, completed_at, status,
                        chunks_scanned, chunks_skipped, signs_found, error_message
                    ) VALUES (?, '2026-08-08T00:00:00Z', '2026-08-08T00:00:01Z',
                              'COMPLETED', 2, 0, 3, NULL)
                    """)) {
                scan.setString(1, scanId.toString());
                scan.executeUpdate();
            }
            try (PreparedStatement sign = connection.prepareStatement("""
                    INSERT INTO legacy_sign_inventory(
                        id, world_name, chunk_x, chunk_z,
                        block_x, block_y, block_z, sign_side,
                        header_line, action_type,
                        raw_line_1, raw_line_2, raw_line_3, raw_line_4,
                        parser_version, review_status, present,
                        first_seen_at, last_seen_at, last_scan_id
                    ) VALUES (?, ?, ?, 0, ?, 64, 0, 'FRONT',
                              '[+train]', ?, '[+train]', ?, ?, ?,
                              1, 'UNREVIEWED', 1,
                              '2026-08-08T00:00:00Z', '2026-08-08T00:00:01Z', ?)
                    """)) {
                insertCandidateSign(
                        sign, departureSignId, "world", 1, 30,
                        "SPAWN", "spawn", "12m", "0.4", scanId);
                insertCandidateSign(
                        sign, motionSignId, "world", 0, 10,
                        "STATION", "station", "2", "continue", scanId);
                insertCandidateSign(
                        sign, reversingSignId, "signless", 0, 5,
                        "STATION", "station", "0", "reverse", scanId);
            }
            try (PreparedStatement attribute = connection.prepareStatement("""
                    INSERT INTO legacy_sign_attribute(sign_id, attribute_key, attribute_value)
                    VALUES (?, ?, ?)
                    """)) {
                insertAttribute(attribute, departureSignId, "spawn_pattern", "12m");
                insertAttribute(attribute, departureSignId, "interval", "30s");
                insertAttribute(attribute, departureSignId, "launch_force", "0.4");
                insertAttribute(attribute, departureSignId, "always_powered", "true");
                insertAttribute(attribute, motionSignId, "instruction", "continue");
                insertAttribute(attribute, motionSignId, "delay", "2");
                insertAttribute(attribute, reversingSignId, "instruction", "reverse");
                insertAttribute(attribute, reversingSignId, "delay", "0");
            }
            connection.commit();
        }
    }

    private static void insertCandidateSign(
            PreparedStatement statement,
            UUID signId,
            String worldName,
            int chunkX,
            int blockX,
            String actionType,
            String lineTwo,
            String lineThree,
            String lineFour,
            UUID scanId) throws Exception {
        statement.setString(1, signId.toString());
        statement.setString(2, worldName);
        statement.setInt(3, chunkX);
        statement.setInt(4, blockX);
        statement.setString(5, actionType);
        statement.setString(6, lineTwo);
        statement.setString(7, lineThree);
        statement.setString(8, lineFour);
        statement.setString(9, scanId.toString());
        statement.executeUpdate();
    }

    private static void insertAttribute(
            PreparedStatement statement,
            UUID signId,
            String key,
            String value) throws Exception {
        statement.setString(1, signId.toString());
        statement.setString(2, key);
        statement.setString(3, value);
        statement.executeUpdate();
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

    private record AuthSession(String cookie, String csrf) {
    }
}
