package dev.advancedtrains.atcs.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.advancedtrains.atcs.config.WebConfiguration;
import dev.advancedtrains.atcs.infrastructure.review.CandidateAdoptionException;
import dev.advancedtrains.atcs.infrastructure.review.CandidateAuthorityInput;
import dev.advancedtrains.atcs.infrastructure.review.CandidateAuthorityRole;
import dev.advancedtrains.atcs.infrastructure.review.CandidateConflictException;
import dev.advancedtrains.atcs.infrastructure.review.CandidateDecisionAction;
import dev.advancedtrains.atcs.infrastructure.review.CandidateDecisionCommand;
import dev.advancedtrains.atcs.infrastructure.review.CandidateDecisionStatus;
import dev.advancedtrains.atcs.infrastructure.review.CandidateKind;
import dev.advancedtrains.atcs.infrastructure.review.CandidateNotFoundException;
import dev.advancedtrains.atcs.infrastructure.review.CandidateReviewItem;
import dev.advancedtrains.atcs.infrastructure.review.InfrastructureCandidateReviewService;
import dev.advancedtrains.atcs.web.AdminDataRepository.OptimisticLockException;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Serves the same-origin administration application and its audited JSON API.
 */
public final class EmbeddedWebServer implements AutoCloseable {
    private static final int MAXIMUM_REQUEST_BYTES = 65536;
    private static final String SESSION_COOKIE = "ATCS_SESSION";
    private static final String CANDIDATE_PATH_PREFIX = "/api/v1/infrastructure-candidates/";
    private static final String DECISION_PATH_SUFFIX = "/decision";
    private final WebConfiguration configuration;
    private final AdminDataRepository repository;
    private final InfrastructureCandidateReviewService candidateReviewService;
    private final Logger logger;
    private final Gson gson = new GsonBuilder()
            .registerTypeAdapterFactory(new OptionalTypeAdapterFactory())
            .registerTypeAdapter(
                    Instant.class,
                    (JsonSerializer<Instant>) (value, type, context) ->
                            new JsonPrimitive(value.toString()))
            .disableHtmlEscaping()
            .create();
    private final WebSessionManager sessionManager;
    private final HttpServer server;
    private final ExecutorService executor;

    public EmbeddedWebServer(
            WebConfiguration configuration,
            AdminDataRepository repository,
            InfrastructureCandidateReviewService candidateReviewService,
            Clock clock,
            Logger logger) throws IOException {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.candidateReviewService = Objects.requireNonNull(
                candidateReviewService, "candidateReviewService");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.sessionManager = new WebSessionManager(configuration, clock);
        this.server = HttpServer.create(
                new InetSocketAddress(configuration.bindAddress(), configuration.port()),
                configuration.backlog());
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    public void start() {
        server.start();
    }

    public String statusMessage() {
        return "listening on http://" + configuration.bindAddress() + ":" + configuration.port();
    }

    private void handle(HttpExchange exchange) {
        try {
            addSecurityHeaders(exchange.getResponseHeaders());
            String path = normalizedPath(exchange.getRequestURI());
            if (path.startsWith("/api/")) {
                handleApi(exchange, path);
            } else {
                handleStatic(exchange, path);
            }
        } catch (CandidateNotFoundException exception) {
            sendJson(exchange, 404, Map.of("error", "CANDIDATE_NOT_FOUND", "message", exception.getMessage()));
        } catch (CandidateConflictException | CandidateAdoptionException exception) {
            sendJson(exchange, 409, Map.of("error", "CANDIDATE_CONFLICT", "message", exception.getMessage()));
        } catch (OptimisticLockException exception) {
            sendJson(exchange, 409, Map.of("error", "VERSION_CONFLICT", "message", exception.getMessage()));
        } catch (IllegalArgumentException | JsonParseException exception) {
            sendJson(exchange, 400, Map.of("error", "INVALID_REQUEST", "message", exception.getMessage()));
        } catch (SQLException exception) {
            logger.log(Level.SEVERE, "Web administration database request failed", exception);
            sendJson(exchange, 500, Map.of("error", "DATABASE_FAILURE", "message", "Database request failed"));
        } catch (Exception exception) {
            logger.log(Level.SEVERE, "Web administration request failed", exception);
            sendJson(exchange, 500, Map.of("error", "INTERNAL_FAILURE", "message", "Request failed"));
        } finally {
            exchange.close();
        }
    }

    private void handleApi(HttpExchange exchange, String path) throws IOException, SQLException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        if ("/api/v1/auth/login".equals(path) && "POST".equals(method)) {
            handleLogin(exchange);
            return;
        }

        WebSessionManager.Session session = requireSession(exchange);
        if (session == null) {
            return;
        }
        if (isMutation(method) && !sessionManager.validCsrf(
                session, exchange.getRequestHeaders().getFirst("X-ATCS-CSRF"))) {
            sendJson(exchange, 403, Map.of("error", "CSRF_REJECTED", "message", "CSRF token is missing or invalid"));
            return;
        }

        if ("/api/v1/auth/session".equals(path) && "GET".equals(method)) {
            sendJson(exchange, 200, new SessionResponse(
                    true, session.role(), session.csrfToken(), session.expiresAt().toString()));
            return;
        }
        if ("/api/v1/auth/logout".equals(path) && "POST".equals(method)) {
            sessionManager.logout(session.id());
            clearSessionCookie(exchange);
            sendJson(exchange, 200, Map.of("ok", true));
            return;
        }
        if ("/api/v1/overview".equals(path) && "GET".equals(method)) {
            sendJson(exchange, 200, repository.loadOverview());
            return;
        }
        if ("/api/v1/worlds".equals(path) && "GET".equals(method)) {
            sendJson(exchange, 200, Map.of("items", repository.listActiveWorlds()));
            return;
        }
        if ("/api/v1/symbols".equals(path) && "GET".equals(method)) {
            handleSymbolList(exchange);
            return;
        }
        if (path.startsWith("/api/v1/symbols/") && path.endsWith("/decision")
                && "PUT".equals(method)) {
            handleSymbolDecision(exchange, path, session);
            return;
        }
        if ("/api/v1/infrastructure-candidates".equals(path) && "GET".equals(method)) {
            handleCandidateList(exchange);
            return;
        }
        if (isCandidateDecisionPath(path) && "PUT".equals(method)) {
            handleCandidateDecision(exchange, path, session);
            return;
        }
        if (isCandidateDetailPath(path) && "GET".equals(method)) {
            handleCandidateDetail(exchange, path);
            return;
        }
        if ("/api/v1/stations".equals(path) && "GET".equals(method)) {
            sendJson(exchange, 200, Map.of("items", repository.listStations()));
            return;
        }
        if ("/api/v1/stations".equals(path) && "POST".equals(method)) {
            StationRequest request = readJson(exchange, StationRequest.class);
            sendJson(exchange, 201, repository.createStation(
                    request.id(), request.displayName(), request.worldName(), actorId(session)));
            return;
        }
        if (path.startsWith("/api/v1/stations/") && path.endsWith("/platforms")
                && "POST".equals(method)) {
            String stationId = path.substring(
                    "/api/v1/stations/".length(),
                    path.length() - "/platforms".length());
            PlatformRequest request = readJson(exchange, PlatformRequest.class);
            sendJson(exchange, 201, repository.createPlatform(
                    stationId,
                    request.id(),
                    request.displayName(),
                    request.x(),
                    request.y(),
                    request.z(),
                    request.berthTolerance(),
                    actorId(session)));
            return;
        }
        if ("/api/v1/lines".equals(path) && "GET".equals(method)) {
            sendJson(exchange, 200, Map.of("items", repository.listLines()));
            return;
        }
        if ("/api/v1/lines".equals(path) && "POST".equals(method)) {
            LineRequest request = readJson(exchange, LineRequest.class);
            sendJson(exchange, 201, repository.createLine(
                    request.id(), request.displayName(), request.colorRgb(), actorId(session)));
            return;
        }
        if (path.startsWith("/api/v1/lines/") && "PUT".equals(method)) {
            String lineId = path.substring("/api/v1/lines/".length());
            LineRequest request = readJson(exchange, LineRequest.class);
            sendJson(exchange, 200, repository.updateLine(
                    lineId,
                    request.displayName(),
                    request.colorRgb(),
                    request.enabled(),
                    request.expectedVersion(),
                    actorId(session)));
            return;
        }
        if ("/api/v1/route-plans".equals(path) && "GET".equals(method)) {
            sendJson(exchange, 200, Map.of("items", repository.listRoutePlans()));
            return;
        }
        if ("/api/v1/route-plans".equals(path) && "POST".equals(method)) {
            RoutePlanRequest request = readJson(exchange, RoutePlanRequest.class);
            sendJson(exchange, 201, repository.saveRoutePlan(
                    request.id(), request.lineId(), request.displayName(),
                    request.directionName(), Optional.ofNullable(request.destinationName()),
                    request.circular(), Optional.ofNullable(request.anchorStationId()),
                    request.terminalPolicy(), Objects.requireNonNullElse(request.calls(), List.of()),
                    0, actorId(session)));
            return;
        }
        if (path.startsWith("/api/v1/route-plans/") && "PUT".equals(method)) {
            String routePlanId = path.substring("/api/v1/route-plans/".length());
            RoutePlanRequest request = readJson(exchange, RoutePlanRequest.class);
            sendJson(exchange, 200, repository.saveRoutePlan(
                    routePlanId, request.lineId(), request.displayName(),
                    request.directionName(), Optional.ofNullable(request.destinationName()),
                    request.circular(), Optional.ofNullable(request.anchorStationId()),
                    request.terminalPolicy(), Objects.requireNonNullElse(request.calls(), List.of()),
                    request.expectedVersion(), actorId(session)));
            return;
        }
        if ("/api/v1/gates".equals(path) && "GET".equals(method)) {
            sendJson(exchange, 200, Map.of("items", repository.listFareGates()));
            return;
        }
        if ("/api/v1/gates".equals(path) && "POST".equals(method)) {
            GateRequest request = readJson(exchange, GateRequest.class);
            sendJson(exchange, 201, repository.createFareGate(
                    request.id(), request.displayName(), request.stationId(),
                    request.x(), request.y(), request.z(), request.gateMode(),
                    request.sensorDefinitionJson(), request.outputDefinitionJson(),
                    request.authorizationTimeoutSeconds(), actorId(session)));
            return;
        }
        if (path.startsWith("/api/v1/gates/") && "PUT".equals(method)) {
            String gateId = path.substring("/api/v1/gates/".length());
            GateRequest request = readJson(exchange, GateRequest.class);
            sendJson(exchange, 200, repository.updateFareGate(
                    gateId, request.displayName(), request.stationId(),
                    request.x(), request.y(), request.z(), request.gateMode(),
                    request.sensorDefinitionJson(), request.outputDefinitionJson(),
                    request.authorizationTimeoutSeconds(), request.enabled(),
                    request.expectedVersion(), actorId(session)));
            return;
        }
        if ("/api/v1/fare-zones".equals(path) && "GET".equals(method)) {
            sendJson(exchange, 200, Map.of("items", repository.listFareZones()));
            return;
        }
        if ("/api/v1/fare-zones".equals(path) && "POST".equals(method)) {
            FareZoneRequest request = readJson(exchange, FareZoneRequest.class);
            sendJson(exchange, 201, repository.createFareZone(
                    request.id(), request.displayName(), request.colorRgb(), actorId(session)));
            return;
        }
        if (path.startsWith("/api/v1/fare-zones/") && "PUT".equals(method)) {
            String zoneId = path.substring("/api/v1/fare-zones/".length());
            FareZoneRequest request = readJson(exchange, FareZoneRequest.class);
            sendJson(exchange, 200, repository.updateFareZone(
                    zoneId, request.displayName(), request.colorRgb(),
                    request.expectedVersion(), actorId(session)));
            return;
        }
        if ("/api/v1/fare-rules".equals(path) && "GET".equals(method)) {
            sendJson(exchange, 200, Map.of("items", repository.listFareRules()));
            return;
        }
        if ("/api/v1/fare-rules".equals(path) && "POST".equals(method)) {
            FareRuleRequest request = readJson(exchange, FareRuleRequest.class);
            sendJson(exchange, 201, repository.createFareRule(
                    request.id(), request.displayName(), request.ruleType(),
                    Optional.ofNullable(request.originStationId()),
                    Optional.ofNullable(request.destinationStationId()),
                    Optional.ofNullable(request.originZoneId()),
                    Optional.ofNullable(request.destinationZoneId()),
                    Optional.ofNullable(request.lineId()),
                    request.amountMinor(), request.currencyCode(), request.priority(),
                    request.bidirectional(), actorId(session)));
            return;
        }
        if (path.startsWith("/api/v1/fare-rules/") && "PUT".equals(method)) {
            String ruleId = path.substring("/api/v1/fare-rules/".length());
            FareRuleRequest request = readJson(exchange, FareRuleRequest.class);
            sendJson(exchange, 200, repository.updateFareRule(
                    ruleId, request.displayName(), request.ruleType(),
                    Optional.ofNullable(request.originStationId()),
                    Optional.ofNullable(request.destinationStationId()),
                    Optional.ofNullable(request.originZoneId()),
                    Optional.ofNullable(request.destinationZoneId()),
                    Optional.ofNullable(request.lineId()),
                    request.amountMinor(), request.currencyCode(), request.priority(),
                    request.bidirectional(), request.enabled(), request.expectedVersion(),
                    actorId(session)));
            return;
        }
        if ("/api/v1/audit".equals(path) && "GET".equals(method)) {
            int limit = parsePositive(
                    parseQuery(exchange.getRequestURI()).getOrDefault("limit", "100"),
                    "limit");
            sendJson(exchange, 200, Map.of("items", repository.listAuditRecords(limit)));
            return;
        }
        if ("/api/v1/network/rails".equals(path) && "GET".equals(method)) {
            Map<String, String> query = parseQuery(exchange.getRequestURI());
            String world = query.get("world");
            if (world == null || world.isBlank()) {
                throw new IllegalArgumentException("The world query parameter is required");
            }
            sendJson(exchange, 200, repository.loadRailGraph(
                    world, configuration.networkMaxElements()));
            return;
        }
        sendJson(exchange, 404, Map.of("error", "NOT_FOUND", "message", "API route not found"));
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        LoginRequest request = readJson(exchange, LoginRequest.class);
        String remoteAddress = exchange.getRemoteAddress() == null
                ? "unknown"
                : exchange.getRemoteAddress().getAddress().getHostAddress();
        WebSessionManager.LoginResult result = sessionManager.login(request.token(), remoteAddress);
        if (result.status() == WebSessionManager.Status.RATE_LIMITED) {
            exchange.getResponseHeaders().set("Retry-After", "60");
            sendJson(exchange, 429, Map.of("error", "RATE_LIMITED", "message", "Too many login attempts"));
            return;
        }
        if (result.status() != WebSessionManager.Status.ACCEPTED) {
            sendJson(exchange, 401, Map.of("error", "LOGIN_FAILED", "message", "Invalid administration token"));
            return;
        }
        WebSessionManager.Session session = result.session().orElseThrow();
        setSessionCookie(exchange, session.id());
        sendJson(exchange, 200, new SessionResponse(
                true, session.role(), session.csrfToken(), session.expiresAt().toString()));
    }

    private void handleSymbolList(HttpExchange exchange) throws SQLException, IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI());
        String world = query.getOrDefault("world", "").strip();
        String type = query.getOrDefault("type", "").strip().toUpperCase(Locale.ROOT);
        String decision = query.getOrDefault("decision", "").strip().toUpperCase(Locale.ROOT);
        String search = query.getOrDefault("search", "").strip().toLowerCase(Locale.ROOT);
        int offset = parseNonNegative(query.getOrDefault("offset", "0"), "offset");
        int limit = Math.min(
                parsePositive(query.getOrDefault(
                        "limit", Integer.toString(configuration.apiPageSize())), "limit"),
                configuration.apiPageSize());

        List<AdminDataRepository.SymbolRow> filtered = repository.listSymbols().stream()
                .filter(item -> world.isEmpty() || item.worldName().equalsIgnoreCase(world))
                .filter(item -> type.isEmpty() || item.type().equalsIgnoreCase(type))
                .filter(item -> decision.isEmpty() || item.decision().equalsIgnoreCase(decision))
                .filter(item -> search.isEmpty() || symbolSearchText(item).contains(search))
                .toList();
        int from = Math.min(offset, filtered.size());
        int to = Math.min(from + limit, filtered.size());
        sendJson(exchange, 200, new PageResponse<>(
                filtered.subList(from, to), filtered.size(), offset, limit));
    }

    private void handleSymbolDecision(
            HttpExchange exchange,
            String path,
            WebSessionManager.Session session) throws IOException, SQLException {
        String rawId = path.substring(
                "/api/v1/symbols/".length(),
                path.length() - "/decision".length());
        UUID signId = UUID.fromString(rawId);
        DecisionRequest request = readJson(exchange, DecisionRequest.class);
        sendJson(exchange, 200, repository.saveSymbolDecision(
                signId,
                request.decision(),
                Optional.ofNullable(request.assignedRole()),
                Optional.ofNullable(request.targetId()),
                Optional.ofNullable(request.note()),
                request.expectedVersion(),
                actorId(session)));
    }

    private void handleCandidateList(HttpExchange exchange) throws SQLException {
        Map<String, String> query = parseQuery(exchange.getRequestURI());
        String world = query.getOrDefault("world", "").strip();
        Optional<CandidateKind> kind = parseOptionalEnum(
                query.get("type"), CandidateKind.class, "type");
        Optional<CandidateDecisionStatus> decision = parseOptionalEnum(
                query.get("decision"), CandidateDecisionStatus.class, "decision");
        Optional<CandidateAuthorityRole> role = parseOptionalEnum(
                query.get("role"), CandidateAuthorityRole.class, "role");
        String search = query.getOrDefault("search", "").strip().toLowerCase(Locale.ROOT);
        int offset = parseNonNegative(query.getOrDefault("offset", "0"), "offset");
        int limit = Math.min(
                parsePositive(query.getOrDefault(
                        "limit", Integer.toString(configuration.apiPageSize())), "limit"),
                configuration.apiPageSize());

        List<CandidateReviewItem> filtered = candidateReviewService
                .listLatestCandidates(kind, decision)
                .stream()
                .filter(item -> world.isEmpty() || item.worldName().equalsIgnoreCase(world))
                .filter(item -> role.isEmpty() || candidateHasRole(item, role.orElseThrow()))
                .filter(item -> search.isEmpty() || candidateSearchText(item).contains(search))
                .sorted(candidateOrder())
                .toList();
        int from = Math.min(offset, filtered.size());
        int to = Math.min(from + limit, filtered.size());
        sendJson(exchange, 200, new PageResponse<>(
                filtered.subList(from, to), filtered.size(), offset, limit));
    }

    private void handleCandidateDetail(HttpExchange exchange, String path) throws SQLException {
        UUID candidateId = candidateIdFromDetailPath(path);
        Optional<CandidateReviewItem> candidate = candidateReviewService.findLatestCandidate(candidateId);
        if (candidate.isEmpty()) {
            sendJson(exchange, 404, Map.of(
                    "error", "CANDIDATE_NOT_FOUND",
                    "message", "Infrastructure candidate was not found in the latest discovery run"));
            return;
        }
        sendJson(exchange, 200, candidate.orElseThrow());
    }

    private void handleCandidateDecision(
            HttpExchange exchange,
            String path,
            WebSessionManager.Session session) throws IOException, SQLException {
        UUID candidateId = candidateIdFromDecisionPath(path);
        CandidateDecisionRequest request = readJson(exchange, CandidateDecisionRequest.class);
        if (request.expectedRunId() == null) {
            throw new IllegalArgumentException("expectedRunId is required");
        }
        if (request.expectedDecisionVersion() == null) {
            throw new IllegalArgumentException("expectedDecisionVersion is required");
        }
        if (request.expectedDecisionVersion() < 0) {
            throw new IllegalArgumentException("expectedDecisionVersion must not be negative");
        }
        CandidateDecisionAction action = parseDecisionAction(request.decision());
        Optional<CandidateAuthorityInput> authority = Optional.ofNullable(request.authority())
                .map(value -> new CandidateAuthorityInput(
                        optionalText(value.targetId()),
                        optionalText(value.displayName())));
        if (action == CandidateDecisionAction.INCLUDE
                && (authority.isEmpty()
                || authority.orElseThrow().targetId().isEmpty()
                || authority.orElseThrow().displayName().isEmpty())) {
            throw new IllegalArgumentException(
                    "authority.targetId and authority.displayName are required when decision is INCLUDED");
        }
        if (action != CandidateDecisionAction.INCLUDE && authority.isPresent()) {
            throw new IllegalArgumentException(
                    "authority must be omitted when decision is IGNORED or PENDING");
        }
        CandidateDecisionCommand command = new CandidateDecisionCommand(
                request.expectedRunId(),
                action,
                request.expectedDecisionVersion(),
                optionalText(request.note()),
                authority,
                actorId(session));
        sendJson(exchange, 200, candidateReviewService.decide(candidateId, command));
    }

    private void handleStatic(HttpExchange exchange, String path) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())
                && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "METHOD_NOT_ALLOWED"));
            return;
        }
        String resourcePath = switch (path) {
            case "/", "/index.html" -> "/web/index.html";
            case "/app.js" -> "/web/app.js";
            case "/network-map.js" -> "/web/network-map.js";
            case "/styles.css" -> "/web/styles.css";
            case "/icons.svg" -> "/web/icons.svg";
            default -> null;
        };
        if (resourcePath == null) {
            sendText(exchange, 404, "text/plain; charset=utf-8", "Not found");
            return;
        }
        try (InputStream input = EmbeddedWebServer.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                sendText(exchange, 404, "text/plain; charset=utf-8", "Asset not found");
                return;
            }
            byte[] body = input.readAllBytes();
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", contentType(resourcePath));
            // Asset filenames are stable inside the plugin JAR, so clients must revalidate
            // them after a plugin update instead of retaining stale JavaScript.
            headers.set("Cache-Control", resourcePath.endsWith("index.html")
                    ? "no-store"
                    : "no-cache");
            exchange.sendResponseHeaders(200, "HEAD".equalsIgnoreCase(exchange.getRequestMethod()) ? -1 : body.length);
            if (!"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseBody().write(body);
            }
        }
    }

    private WebSessionManager.Session requireSession(HttpExchange exchange) throws IOException {
        String sessionId = cookieValue(exchange.getRequestHeaders(), SESSION_COOKIE).orElse(null);
        Optional<WebSessionManager.Session> session = sessionManager.findSession(sessionId);
        if (session.isEmpty()) {
            clearSessionCookie(exchange);
            sendJson(exchange, 401, Map.of("error", "AUTHENTICATION_REQUIRED", "message", "Log in with the administration token"));
            return null;
        }
        return session.orElseThrow();
    }

    private <T> T readJson(HttpExchange exchange, Class<T> type) throws IOException {
        String contentType = Objects.requireNonNullElse(
                exchange.getRequestHeaders().getFirst("Content-Type"), "");
        if (!contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new IllegalArgumentException("Content-Type must be application/json");
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAXIMUM_REQUEST_BYTES + 1);
        if (body.length > MAXIMUM_REQUEST_BYTES) {
            throw new IllegalArgumentException("Request body is too large");
        }
        T parsed = gson.fromJson(new String(body, StandardCharsets.UTF_8), type);
        if (parsed == null) {
            throw new IllegalArgumentException("JSON request body is required");
        }
        return parsed;
    }

    private void sendJson(HttpExchange exchange, int status, Object body) {
        try {
            sendText(exchange, status, "application/json; charset=utf-8", gson.toJson(body));
        } catch (IOException exception) {
            logger.log(Level.FINE, "Could not write web response", exception);
        }
    }

    private void sendText(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private void setSessionCookie(HttpExchange exchange, String sessionId) {
        String cookie = SESSION_COOKIE + "=" + sessionId
                + "; Path=/; HttpOnly; SameSite=Strict; Max-Age="
                + configuration.sessionMinutes() * 60
                + (configuration.secureCookie() ? "; Secure" : "");
        exchange.getResponseHeaders().add("Set-Cookie", cookie);
    }

    private void clearSessionCookie(HttpExchange exchange) {
        exchange.getResponseHeaders().add(
                "Set-Cookie",
                SESSION_COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0"
                        + (configuration.secureCookie() ? "; Secure" : ""));
    }

    private static void addSecurityHeaders(Headers headers) {
        headers.set("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; "
                        + "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; "
                        + "base-uri 'none'; form-action 'self'");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
    }

    private static Optional<String> cookieValue(Headers headers, String name) {
        List<String> cookieHeaders = headers.getOrDefault("Cookie", List.of());
        for (String header : cookieHeaders) {
            for (String part : header.split(";")) {
                String[] pair = part.strip().split("=", 2);
                if (pair.length == 2 && pair[0].equals(name)) {
                    return Optional.of(pair[1]);
                }
            }
        }
        return Optional.empty();
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> values = new LinkedHashMap<>();
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return values;
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 2
                    ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                    : "";
            values.put(key, value);
        }
        return values;
    }

    private static String normalizedPath(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.length() > 1 && path.endsWith("/")
                ? path.substring(0, path.length() - 1)
                : path;
    }

    private static boolean isMutation(String method) {
        return Set.of("POST", "PUT", "PATCH", "DELETE").contains(method);
    }

    private static boolean isCandidateDetailPath(String path) {
        if (!path.startsWith(CANDIDATE_PATH_PREFIX)) {
            return false;
        }
        String remainder = path.substring(CANDIDATE_PATH_PREFIX.length());
        return !remainder.isBlank() && !remainder.contains("/");
    }

    private static boolean isCandidateDecisionPath(String path) {
        if (!path.startsWith(CANDIDATE_PATH_PREFIX) || !path.endsWith(DECISION_PATH_SUFFIX)) {
            return false;
        }
        String remainder = path.substring(
                CANDIDATE_PATH_PREFIX.length(),
                path.length() - DECISION_PATH_SUFFIX.length());
        return !remainder.isBlank() && !remainder.contains("/");
    }

    private static UUID candidateIdFromDetailPath(String path) {
        return parseCandidateId(path.substring(CANDIDATE_PATH_PREFIX.length()));
    }

    private static UUID candidateIdFromDecisionPath(String path) {
        return parseCandidateId(path.substring(
                CANDIDATE_PATH_PREFIX.length(),
                path.length() - DECISION_PATH_SUFFIX.length()));
    }

    private static UUID parseCandidateId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("candidateId must be a UUID", exception);
        }
    }

    private static int parseNonNegative(String value, String label) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new IllegalArgumentException(label + " must not be negative");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " must be an integer", exception);
        }
    }

    private static int parsePositive(String value, String label) {
        int parsed = parseNonNegative(value, label);
        if (parsed == 0) {
            throw new IllegalArgumentException(label + " must be positive");
        }
        return parsed;
    }

    private static <E extends Enum<E>> Optional<E> parseOptionalEnum(
            String value,
            Class<E> type,
            String label) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(label + " has an unsupported value", exception);
        }
    }

    private static CandidateDecisionAction parseDecisionAction(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("decision is required");
        }
        return switch (value.strip().toUpperCase(Locale.ROOT)) {
            case "INCLUDED" -> CandidateDecisionAction.INCLUDE;
            case "IGNORED" -> CandidateDecisionAction.IGNORE;
            case "PENDING" -> CandidateDecisionAction.CLEAR;
            default -> throw new IllegalArgumentException(
                    "decision must be INCLUDED, IGNORED, or PENDING");
        };
    }

    private static Optional<String> optionalText(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.strip());
    }

    private static Comparator<CandidateReviewItem> candidateOrder() {
        return Comparator
                .comparing((CandidateReviewItem item) -> item.worldName().toLowerCase(Locale.ROOT))
                .thenComparingInt(CandidateReviewItem::blockX)
                .thenComparingInt(CandidateReviewItem::blockY)
                .thenComparingInt(CandidateReviewItem::blockZ)
                .thenComparing(item -> item.kind().name())
                .thenComparing(item -> item.candidateId().toString());
    }

    private static boolean candidateHasRole(
            CandidateReviewItem item,
            CandidateAuthorityRole expectedRole) {
        return item.decision().authorityRole()
                .filter(expectedRole::equals)
                .isPresent()
                || item.suggestedRole().filter(expectedRole::equals).isPresent();
    }

    private static String candidateSearchText(CandidateReviewItem item) {
        List<String> parts = new ArrayList<>();
        parts.add(item.discoveryRunId().toString());
        parts.add(item.candidateId().toString());
        parts.add(item.sourceSignId().toString());
        parts.add(item.kind().name());
        parts.add(item.worldName());
        parts.add(item.blockX() + " " + item.blockY() + " " + item.blockZ());
        parts.add(item.signSide());
        parts.add(item.actionType());
        parts.addAll(item.rawLines());
        item.attributes().forEach((key, value) -> parts.add(key + " " + value));
        item.candidateProperties().forEach((key, value) -> parts.add(key + " " + value));
        item.evidence().forEach(evidence -> {
            parts.add(evidence.kind());
            evidence.evidenceText().ifPresent(parts::add);
        });
        item.suggestedRole().ifPresent(value -> parts.add(value.name()));
        item.decision().authorityRole().ifPresent(value -> parts.add(value.name()));
        item.decision().targetId().ifPresent(parts::add);
        item.decision().authorityDisplayName().ifPresent(parts::add);
        item.decision().note().ifPresent(parts::add);
        item.decision().staleReason().ifPresent(parts::add);
        return String.join(" ", parts).toLowerCase(Locale.ROOT);
    }

    private static String symbolSearchText(AdminDataRepository.SymbolRow row) {
        List<String> parts = new ArrayList<>(row.lines());
        parts.add(row.id().toString());
        parts.add(row.type());
        parts.add(row.worldName());
        parts.add(row.x() + " " + row.y() + " " + row.z());
        return String.join(" ", parts).toLowerCase(Locale.ROOT);
    }

    private static String contentType(String resourcePath) {
        if (resourcePath.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (resourcePath.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (resourcePath.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (resourcePath.endsWith(".svg")) {
            return "image/svg+xml; charset=utf-8";
        }
        return "application/octet-stream";
    }

    private static String actorId(WebSessionManager.Session session) {
        return "session:" + session.id().substring(0, 12);
    }

    @Override
    public void close() {
        server.stop(1);
        executor.shutdown();
    }

    private record LoginRequest(String token) {
    }

    private record DecisionRequest(
            String decision,
            String assignedRole,
            String targetId,
            String note,
            int expectedVersion) {
    }

    private record CandidateDecisionRequest(
            UUID expectedRunId,
            Integer expectedDecisionVersion,
            String decision,
            String note,
            CandidateAuthorityRequest authority) {
    }

    private record CandidateAuthorityRequest(String targetId, String displayName) {
    }

    private record StationRequest(String id, String displayName, String worldName) {
    }

    private record PlatformRequest(
            String id,
            String displayName,
            int x,
            int y,
            int z,
            double berthTolerance) {
    }

    private record LineRequest(
            String id,
            String displayName,
            int colorRgb,
            boolean enabled,
            int expectedVersion) {
    }

    private record RoutePlanRequest(
            String id,
            String lineId,
            String displayName,
            String directionName,
            String destinationName,
            boolean circular,
            String anchorStationId,
            String terminalPolicy,
            List<AdminDataRepository.RouteCallInput> calls,
            int expectedVersion) {
    }

    private record GateRequest(
            String id,
            String displayName,
            String stationId,
            int x,
            int y,
            int z,
            String gateMode,
            String sensorDefinitionJson,
            String outputDefinitionJson,
            int authorizationTimeoutSeconds,
            boolean enabled,
            int expectedVersion) {
    }

    private record FareZoneRequest(
            String id,
            String displayName,
            int colorRgb,
            int expectedVersion) {
    }

    private record FareRuleRequest(
            String id,
            String displayName,
            String ruleType,
            String originStationId,
            String destinationStationId,
            String originZoneId,
            String destinationZoneId,
            String lineId,
            int amountMinor,
            String currencyCode,
            int priority,
            boolean bidirectional,
            boolean enabled,
            int expectedVersion) {
    }

    private record SessionResponse(
            boolean authenticated,
            String role,
            String csrfToken,
            String expiresAt) {
    }

    private record PageResponse<T>(List<T> items, int total, int offset, int limit) {
    }
}
