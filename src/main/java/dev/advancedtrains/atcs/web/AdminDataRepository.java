package dev.advancedtrains.atcs.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import dev.advancedtrains.atcs.domain.DomainId;
import dev.advancedtrains.atcs.persistence.SQLiteDatabase;
import dev.advancedtrains.atcs.world.RailwayWorldPolicy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Provides versioned, audited editor access without mutating Minecraft worlds.
 */
public final class AdminDataRepository {
    private static final int DEFAULT_MAXIMUM_SOURCE_NODES = 1_000_000;
    private static final int DEFAULT_MAXIMUM_SOURCE_EDGES = 2_000_000;
    private static final int MAXIMUM_COMPLETED_SOURCE_ENTRIES = 8;
    private static final long MAXIMUM_COMPLETED_SOURCE_NODES = 1_000_000L;
    private static final long MAXIMUM_COMPLETED_SOURCE_EDGES = 2_000_000L;
    private static final int MAXIMUM_COMPLETED_RESPONSE_ENTRIES = 16;
    private static final long MAXIMUM_COMPLETED_RESPONSE_NODES = 1_000_000L;
    private static final long MAXIMUM_COMPLETED_RESPONSE_EDGES = 2_000_000L;
    private final SQLiteDatabase database;
    private final RailwayWorldPolicy worldPolicy;
    private final Clock clock;
    private final int maximumSourceNodes;
    private final int maximumSourceEdges;
    private final Object railGraphCacheLock = new Object();
    private final Map<RailGraphCacheKey, CompletableFuture<RailGraphSource>> sourceBuildsInFlight =
            new HashMap<>();
    private final LinkedHashMap<RailGraphCacheKey, RailGraphSource> completedSourceCache =
            new LinkedHashMap<>(16, 0.75F, true);
    private final Map<RailGraphResponseKey, CompletableFuture<RailGraph>> responseBuildsInFlight =
            new HashMap<>();
    private final LinkedHashMap<RailGraphResponseKey, RailGraph> completedResponseCache =
            new LinkedHashMap<>(16, 0.75F, true);
    private final Map<RailRunWorld, RailGraphCacheKey> currentSourceKeys = new HashMap<>();
    private final Semaphore sourceMaterializationPermits = new Semaphore(1, true);
    private final Semaphore responseRenderPermits = new Semaphore(1, true);
    private final AtomicLong railGraphMaterializations = new AtomicLong();
    private final AtomicLong railGraphResponseRenders = new AtomicLong();
    private long completedSourceNodes;
    private long completedSourceEdges;
    private long completedResponseNodes;
    private long completedResponseEdges;
    private RailRunRevision newestCachedRailRun;
    private final Gson gson = new GsonBuilder()
            .registerTypeAdapterFactory(new OptionalTypeAdapterFactory())
            .create();

    public AdminDataRepository(
            SQLiteDatabase database,
            RailwayWorldPolicy worldPolicy,
            Clock clock) {
        this(
                database, worldPolicy, clock,
                DEFAULT_MAXIMUM_SOURCE_NODES, DEFAULT_MAXIMUM_SOURCE_EDGES);
    }

    AdminDataRepository(
            SQLiteDatabase database,
            RailwayWorldPolicy worldPolicy,
            Clock clock,
            int maximumSourceNodes,
            int maximumSourceEdges) {
        this.database = Objects.requireNonNull(database, "database");
        this.worldPolicy = Objects.requireNonNull(worldPolicy, "worldPolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maximumSourceNodes <= 0 || maximumSourceEdges <= 0) {
            throw new IllegalArgumentException("Rail graph source limits must be positive");
        }
        this.maximumSourceNodes = maximumSourceNodes;
        this.maximumSourceEdges = maximumSourceEdges;
    }

    public Overview loadOverview() throws SQLException {
        List<SymbolRow> symbols = listSymbols();
        List<StationRow> stations = listStations();
        int pending = 0;
        int included = 0;
        int ignored = 0;
        for (SymbolRow symbol : symbols) {
            switch (symbol.decision()) {
                case "PENDING" -> pending++;
                case "INCLUDED" -> included++;
                case "IGNORED" -> ignored++;
                default -> throw new IllegalStateException("Unknown symbol decision: " + symbol.decision());
            }
        }
        try (Connection connection = database.openConnection()) {
            return new Overview(
                    symbols.size(),
                    pending,
                    included,
                    ignored,
                    stations.size(),
                    stations.stream().mapToInt(station -> station.platforms().size()).sum(),
                    count(connection, "line"),
                    count(connection, "fare_gate"),
                    count(connection, "fare_rule"),
                    loadRailRunOverview(connection));
        }
    }

    public List<String> listActiveWorlds() {
        java.util.TreeSet<String> worlds = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        worlds.addAll(worldPolicy.activeWorldNames());
        return List.copyOf(worlds);
    }

    public List<SymbolRow> listSymbols() throws SQLException {
        List<SymbolRow> rows = new ArrayList<>();
        try (Connection connection = database.openConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT inventory.id, inventory.world_name,
                            inventory.block_x, inventory.block_y, inventory.block_z,
                            inventory.sign_side, inventory.action_type,
                            inventory.raw_line_1, inventory.raw_line_2,
                            inventory.raw_line_3, inventory.raw_line_4,
                            COALESCE(decision.decision, 'PENDING'),
                            decision.assigned_role, decision.target_id,
                            decision.note, COALESCE(decision.version, 0)
                     FROM legacy_sign_inventory inventory
                     LEFT JOIN infrastructure_symbol_decision decision
                            ON decision.source_sign_id = inventory.id
                     WHERE inventory.present = 1
                     ORDER BY inventory.world_name, inventory.block_x,
                              inventory.block_y, inventory.block_z, inventory.sign_side
                     """);
             ResultSet result = query.executeQuery()) {
            while (result.next()) {
                String worldName = result.getString(2);
                if (!worldPolicy.isActive(worldName)) {
                    continue;
                }
                rows.add(new SymbolRow(
                        UUID.fromString(result.getString(1)),
                        worldName,
                        result.getInt(3),
                        result.getInt(4),
                        result.getInt(5),
                        result.getString(6),
                        result.getString(7),
                        List.of(
                                result.getString(8),
                                result.getString(9),
                                result.getString(10),
                                result.getString(11)),
                        result.getString(12),
                        Optional.ofNullable(result.getString(13)),
                        Optional.ofNullable(result.getString(14)),
                        Optional.ofNullable(result.getString(15)),
                        result.getInt(16)));
            }
        }
        return List.copyOf(rows);
    }

    public SymbolDecision saveSymbolDecision(
            UUID signId,
            String requestedDecision,
            Optional<String> assignedRole,
            Optional<String> targetId,
            Optional<String> note,
            int expectedVersion,
            String actorId) throws SQLException {
        Objects.requireNonNull(signId, "signId");
        String decision = requestedDecision.strip().toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("PENDING", "INCLUDED", "IGNORED").contains(decision)) {
            throw new IllegalArgumentException("Decision must be PENDING, INCLUDED, or IGNORED");
        }
        Optional<String> normalizedRole = assignedRole
                .filter(value -> !value.isBlank())
                .map(value -> value.strip().toUpperCase(java.util.Locale.ROOT));
        Set<String> allowedRoles = Set.of(
                "PASSENGER_PLATFORM",
                "DEPARTURE_POINT",
                "REVERSING_CONTROL",
                "MOTION_CONTROL",
                "TRACK_MARKER",
                "OTHER");
        if (normalizedRole.isPresent() && !allowedRoles.contains(normalizedRole.orElseThrow())) {
            throw new IllegalArgumentException("Unknown assigned symbol role");
        }
        if ("INCLUDED".equals(decision) && normalizedRole.isEmpty()) {
            throw new IllegalArgumentException("An included symbol requires an assigned role");
        }
        if (!"INCLUDED".equals(decision)) {
            normalizedRole = Optional.empty();
            targetId = Optional.empty();
        }

        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                requireEditableSign(connection, signId);
                Optional<SymbolDecision> before = loadDecision(connection, signId);
                int currentVersion = before.map(SymbolDecision::version).orElse(0);
                if (currentVersion != expectedVersion) {
                    throw new OptimisticLockException(
                            "Symbol decision changed from version " + expectedVersion
                                    + " to " + currentVersion);
                }
                if ("PENDING".equals(decision)) {
                    try (PreparedStatement delete = connection.prepareStatement(
                            "DELETE FROM infrastructure_symbol_decision WHERE source_sign_id = ?")) {
                        delete.setString(1, signId.toString());
                        delete.executeUpdate();
                    }
                    audit(connection, actorId, "SYMBOL_DECISION_CLEARED", "LEGACY_SIGN",
                            signId.toString(), before.orElse(null), null);
                    connection.commit();
                    return new SymbolDecision("PENDING", Optional.empty(), Optional.empty(),
                            Optional.empty(), 0);
                }

                int newVersion = currentVersion + 1;
                Instant now = Instant.now(clock);
                try (PreparedStatement upsert = connection.prepareStatement("""
                        INSERT INTO infrastructure_symbol_decision(
                            source_sign_id, decision, assigned_role, target_id, note,
                            version, decided_by, decided_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(source_sign_id) DO UPDATE SET
                            decision = excluded.decision,
                            assigned_role = excluded.assigned_role,
                            target_id = excluded.target_id,
                            note = excluded.note,
                            version = excluded.version,
                            decided_by = excluded.decided_by,
                            decided_at = excluded.decided_at
                        """)) {
                    upsert.setString(1, signId.toString());
                    upsert.setString(2, decision);
                    setOptionalString(upsert, 3, normalizedRole);
                    setOptionalString(upsert, 4, cleanOptional(targetId));
                    setOptionalString(upsert, 5, cleanOptional(note));
                    upsert.setInt(6, newVersion);
                    upsert.setString(7, actorId);
                    upsert.setString(8, now.toString());
                    upsert.executeUpdate();
                }
                SymbolDecision after = new SymbolDecision(
                        decision,
                        normalizedRole,
                        cleanOptional(targetId),
                        cleanOptional(note),
                        newVersion);
                audit(connection, actorId, "SYMBOL_DECISION_UPDATED", "LEGACY_SIGN",
                        signId.toString(), before.orElse(null), after);
                connection.commit();
                return after;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public List<StationRow> listStations() throws SQLException {
        List<StationRow> stations = new ArrayList<>();
        try (Connection connection = database.openConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT station.id, station.display_name, station.world_name,
                            station.enabled, station.version,
                            platform.id, platform.display_name,
                            platform.block_x, platform.block_y, platform.block_z,
                            platform.berth_tolerance_blocks, platform.enabled, platform.version
                     FROM station
                     LEFT JOIN platform ON platform.station_id = station.id
                     ORDER BY station.display_name, station.id, platform.display_name, platform.id
                     """);
             ResultSet result = query.executeQuery()) {
            StationBuilder current = null;
            while (result.next()) {
                if (!worldPolicy.isActive(result.getString(3))) {
                    continue;
                }
                String stationId = result.getString(1);
                if (current == null || !current.id.equals(stationId)) {
                    if (current != null) {
                        stations.add(current.freeze());
                    }
                    current = new StationBuilder(
                            stationId,
                            result.getString(2),
                            result.getString(3),
                            result.getInt(4) != 0,
                            result.getInt(5));
                }
                String platformId = result.getString(6);
                if (platformId != null) {
                    current.platforms.add(new PlatformRow(
                            platformId,
                            result.getString(7),
                            result.getInt(8),
                            result.getInt(9),
                            result.getInt(10),
                            result.getDouble(11),
                            result.getInt(12) != 0,
                            result.getInt(13)));
                }
            }
            if (current != null) {
                stations.add(current.freeze());
            }
        }
        return List.copyOf(stations);
    }

    public StationRow createStation(
            String requestedId,
            String displayName,
            String worldName,
            String actorId) throws SQLException {
        String stationId = DomainId.of(requestedId).toString();
        String name = requireText(displayName, "Station display name");
        String world = requireText(worldName, "Station world");
        if (!worldPolicy.isActive(world)) {
            throw new IllegalArgumentException("Station world is not active in TrainCarts");
        }
        Instant now = Instant.now(clock);
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO station(
                        id, display_name, world_name, enabled, version, created_at, updated_at
                    ) VALUES (?, ?, ?, 1, 1, ?, ?)
                    """)) {
                insert.setString(1, stationId);
                insert.setString(2, name);
                insert.setString(3, world);
                insert.setString(4, now.toString());
                insert.setString(5, now.toString());
                insert.executeUpdate();
                StationRow station = new StationRow(stationId, name, world, true, 1, List.of());
                audit(connection, actorId, "STATION_CREATED", "STATION", stationId, null, station);
                connection.commit();
                return station;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public PlatformRow createPlatform(
            String stationIdInput,
            String requestedPlatformId,
            String displayName,
            int blockX,
            int blockY,
            int blockZ,
            double berthTolerance,
            String actorId) throws SQLException {
        String stationId = DomainId.of(stationIdInput).toString();
        String platformId = DomainId.of(requestedPlatformId).toString();
        String name = requireText(displayName, "Platform display name");
        if (!Double.isFinite(berthTolerance) || berthTolerance <= 0) {
            throw new IllegalArgumentException("Berth tolerance must be finite and positive");
        }
        Instant now = Instant.now(clock);
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                String worldName;
                try (PreparedStatement stationQuery = connection.prepareStatement(
                        "SELECT world_name FROM station WHERE id = ?")) {
                    stationQuery.setString(1, stationId);
                    try (ResultSet stationResult = stationQuery.executeQuery()) {
                        if (!stationResult.next()) {
                            throw new IllegalArgumentException("Unknown station: " + stationId);
                        }
                        worldName = stationResult.getString(1);
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO platform(
                            id, station_id, display_name, world_name,
                            block_x, block_y, block_z, berth_tolerance_blocks,
                            enabled, version, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, 1, ?, ?)
                        """)) {
                    insert.setString(1, platformId);
                    insert.setString(2, stationId);
                    insert.setString(3, name);
                    insert.setString(4, worldName);
                    insert.setInt(5, blockX);
                    insert.setInt(6, blockY);
                    insert.setInt(7, blockZ);
                    insert.setDouble(8, berthTolerance);
                    insert.setString(9, now.toString());
                    insert.setString(10, now.toString());
                    insert.executeUpdate();
                }
                PlatformRow platform = new PlatformRow(
                        platformId, name, blockX, blockY, blockZ, berthTolerance, true, 1);
                audit(connection, actorId, "PLATFORM_CREATED", "PLATFORM",
                        stationId + ":" + platformId, null, platform);
                connection.commit();
                return platform;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public List<LineRow> listLines() throws SQLException {
        List<LineRow> rows = new ArrayList<>();
        try (Connection connection = database.openConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT id, display_name, color_rgb, enabled, version
                     FROM line
                     ORDER BY display_name, id
                     """);
             ResultSet result = query.executeQuery()) {
            while (result.next()) {
                rows.add(new LineRow(
                        result.getString(1),
                        result.getString(2),
                        result.getInt(3),
                        result.getInt(4) != 0,
                        result.getInt(5)));
            }
        }
        return List.copyOf(rows);
    }

    public LineRow createLine(
            String requestedId,
            String displayName,
            int colorRgb,
            String actorId) throws SQLException {
        String lineId = DomainId.of(requestedId).toString();
        String name = requireText(displayName, "Line display name");
        requireColor(colorRgb);
        Instant now = Instant.now(clock);
        LineRow line = new LineRow(lineId, name, colorRgb, true, 1);
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO line(
                        id, display_name, color_rgb, enabled, version, created_at, updated_at
                    ) VALUES (?, ?, ?, 1, 1, ?, ?)
                    """)) {
                insert.setString(1, lineId);
                insert.setString(2, name);
                insert.setInt(3, colorRgb);
                insert.setString(4, now.toString());
                insert.setString(5, now.toString());
                insert.executeUpdate();
                audit(connection, actorId, "LINE_CREATED", "LINE", lineId, null, line);
                connection.commit();
                return line;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public LineRow updateLine(
            String requestedId,
            String displayName,
            int colorRgb,
            boolean enabled,
            int expectedVersion,
            String actorId) throws SQLException {
        String lineId = DomainId.of(requestedId).toString();
        String name = requireText(displayName, "Line display name");
        requireColor(colorRgb);
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                LineRow before = requireLine(connection, lineId);
                if (before.version() != expectedVersion) {
                    throw versionConflict("Line", lineId, expectedVersion, before.version());
                }
                Instant now = Instant.now(clock);
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE line
                        SET display_name = ?, color_rgb = ?, enabled = ?,
                            version = version + 1, updated_at = ?
                        WHERE id = ? AND version = ?
                        """)) {
                    update.setString(1, name);
                    update.setInt(2, colorRgb);
                    update.setInt(3, enabled ? 1 : 0);
                    update.setString(4, now.toString());
                    update.setString(5, lineId);
                    update.setInt(6, expectedVersion);
                    requireSingleUpdate(update, "Line changed during update");
                }
                LineRow after = new LineRow(
                        lineId, name, colorRgb, enabled, expectedVersion + 1);
                audit(connection, actorId, "LINE_UPDATED", "LINE", lineId, before, after);
                connection.commit();
                return after;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public List<RoutePlanRow> listRoutePlans() throws SQLException {
        List<RoutePlanRow> rows = new ArrayList<>();
        try (Connection connection = database.openConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT plan.id, plan.line_id, plan.display_name,
                            plan.direction_name, plan.destination_name,
                            plan.circular, plan.anchor_station_id, plan.terminal_policy,
                            plan.track_assignment_state, plan.enabled, plan.version,
                            call.sequence_number, call.station_id, call.platform_id,
                            call.stop_policy, call.dwell_seconds
                     FROM route_plan plan
                     LEFT JOIN route_plan_call call ON call.route_plan_id = plan.id
                     ORDER BY plan.line_id, plan.display_name, plan.id, call.sequence_number
                     """);
             ResultSet result = query.executeQuery()) {
            RoutePlanBuilder current = null;
            while (result.next()) {
                String routeId = result.getString(1);
                if (current == null || !current.id.equals(routeId)) {
                    if (current != null) {
                        rows.add(current.freeze());
                    }
                    current = new RoutePlanBuilder(
                            routeId,
                            result.getString(2),
                            result.getString(3),
                            result.getString(4),
                            Optional.ofNullable(result.getString(5)),
                            result.getInt(6) != 0,
                            Optional.ofNullable(result.getString(7)),
                            result.getString(8),
                            result.getString(9),
                            result.getInt(10) != 0,
                            result.getInt(11));
                }
                if (result.getObject(12) != null) {
                    current.calls.add(new RouteCallRow(
                            result.getInt(12),
                            result.getString(13),
                            Optional.ofNullable(result.getString(14)),
                            result.getString(15),
                            result.getInt(16)));
                }
            }
            if (current != null) {
                rows.add(current.freeze());
            }
        }
        return List.copyOf(rows);
    }

    public RoutePlanRow saveRoutePlan(
            String requestedId,
            String lineIdInput,
            String displayName,
            String directionName,
            Optional<String> destinationName,
            boolean circular,
            Optional<String> anchorStationId,
            String terminalPolicyInput,
            List<RouteCallInput> requestedCalls,
            int expectedVersion,
            String actorId) throws SQLException {
        String routeId = DomainId.of(requestedId).toString();
        String lineId = DomainId.of(lineIdInput).toString();
        String name = requireText(displayName, "Route display name");
        String direction = requireText(directionName, "Route direction name");
        Optional<String> destination = cleanOptional(destinationName);
        Optional<String> anchorStation = cleanDomainId(anchorStationId);
        String terminalPolicy = requireEnum(
                terminalPolicyInput,
                "Terminal policy",
                Set.of("TURNBACK", "CONTINUE_AS", "DEPOT", "DEADHEAD", "DESPAWN", "LOOP_CONTINUE"));
        if (circular && anchorStation.isEmpty()) {
            throw new IllegalArgumentException("A circular route plan requires an anchor station");
        }
        if (circular && !"LOOP_CONTINUE".equals(terminalPolicy)) {
            throw new IllegalArgumentException("A circular route plan requires LOOP_CONTINUE");
        }
        if (!circular && "LOOP_CONTINUE".equals(terminalPolicy)) {
            throw new IllegalArgumentException("Only a circular route plan can use LOOP_CONTINUE");
        }
        Objects.requireNonNull(requestedCalls, "requestedCalls");
        List<RouteCallRow> calls = normalizeRouteCalls(requestedCalls);
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                requireLine(connection, lineId);
                Optional<RoutePlanRow> before = loadRoutePlan(connection, routeId);
                int currentVersion = before.map(RoutePlanRow::version).orElse(0);
                if (currentVersion != expectedVersion) {
                    throw versionConflict("Route plan", routeId, expectedVersion, currentVersion);
                }
                Instant now = Instant.now(clock);
                int newVersion = currentVersion + 1;
                String trackAssignmentState = before
                        .map(RoutePlanRow::trackAssignmentState)
                        .orElse("UNASSIGNED");
                boolean enabled = before.map(RoutePlanRow::enabled).orElse(false)
                        && "COMPLETE".equals(trackAssignmentState);
                if (currentVersion == 0) {
                    try (PreparedStatement insert = connection.prepareStatement("""
                            INSERT INTO route_plan(
                                id, line_id, display_name, direction_name, destination_name,
                                circular, anchor_station_id, terminal_policy,
                                track_assignment_state, enabled, version, created_at, updated_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'UNASSIGNED', 0, 1, ?, ?)
                            """)) {
                        bindRoutePlan(insert, routeId, lineId, name, direction, destination,
                                circular, anchorStation, terminalPolicy);
                        insert.setString(9, now.toString());
                        insert.setString(10, now.toString());
                        insert.executeUpdate();
                    }
                } else {
                    try (PreparedStatement update = connection.prepareStatement("""
                            UPDATE route_plan
                            SET line_id = ?, display_name = ?, direction_name = ?,
                                destination_name = ?, circular = ?, anchor_station_id = ?,
                                terminal_policy = ?, enabled = ?, version = version + 1,
                                updated_at = ?
                            WHERE id = ? AND version = ?
                            """)) {
                        update.setString(1, lineId);
                        update.setString(2, name);
                        update.setString(3, direction);
                        setOptionalString(update, 4, destination);
                        update.setInt(5, circular ? 1 : 0);
                        setOptionalString(update, 6, anchorStation);
                        update.setString(7, terminalPolicy);
                        update.setInt(8, enabled ? 1 : 0);
                        update.setString(9, now.toString());
                        update.setString(10, routeId);
                        update.setInt(11, expectedVersion);
                        requireSingleUpdate(update, "Route plan changed during update");
                    }
                    try (PreparedStatement delete = connection.prepareStatement(
                            "DELETE FROM route_plan_call WHERE route_plan_id = ?")) {
                        delete.setString(1, routeId);
                        delete.executeUpdate();
                    }
                }
                insertRouteCalls(connection, routeId, calls);
                RoutePlanRow after = new RoutePlanRow(
                        routeId, lineId, name, direction, destination, circular,
                        anchorStation, terminalPolicy, trackAssignmentState,
                        enabled, newVersion, calls);
                audit(connection, actorId,
                        currentVersion == 0 ? "ROUTE_PLAN_CREATED" : "ROUTE_PLAN_UPDATED",
                        "ROUTE_PLAN", routeId, before.orElse(null), after);
                connection.commit();
                return after;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public List<FareGateRow> listFareGates() throws SQLException {
        List<FareGateRow> rows = new ArrayList<>();
        try (Connection connection = database.openConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT id, display_name, station_id, world_name,
                            block_x, block_y, block_z, gate_mode,
                            sensor_definition_json, output_definition_json,
                            authorization_timeout_seconds, enabled, version
                     FROM fare_gate
                     ORDER BY station_id, display_name, id
                     """);
             ResultSet result = query.executeQuery()) {
            while (result.next()) {
                rows.add(readFareGate(result));
            }
        }
        return List.copyOf(rows);
    }

    public FareGateRow createFareGate(
            String requestedId,
            String displayName,
            String stationIdInput,
            int blockX,
            int blockY,
            int blockZ,
            String gateModeInput,
            String sensorDefinitionJson,
            String outputDefinitionJson,
            int authorizationTimeoutSeconds,
            String actorId) throws SQLException {
        String gateId = DomainId.of(requestedId).toString();
        String name = requireText(displayName, "Gate display name");
        String stationId = DomainId.of(stationIdInput).toString();
        String gateMode = requireEnum(
                gateModeInput, "Gate mode", Set.of("ENTRY", "EXIT", "BIDIRECTIONAL"));
        String sensorJson = requireJsonObject(sensorDefinitionJson, "Sensor definition");
        String outputJson = requireJsonObject(outputDefinitionJson, "Output definition");
        if (authorizationTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("Authorization timeout must be positive");
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                String worldName = requireStationWorld(connection, stationId);
                Instant now = Instant.now(clock);
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO fare_gate(
                            id, display_name, station_id, world_name,
                            block_x, block_y, block_z, gate_mode,
                            sensor_definition_json, output_definition_json,
                            authorization_timeout_seconds, enabled, version,
                            created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1, ?, ?)
                        """)) {
                    insert.setString(1, gateId);
                    insert.setString(2, name);
                    insert.setString(3, stationId);
                    insert.setString(4, worldName);
                    insert.setInt(5, blockX);
                    insert.setInt(6, blockY);
                    insert.setInt(7, blockZ);
                    insert.setString(8, gateMode);
                    insert.setString(9, sensorJson);
                    insert.setString(10, outputJson);
                    insert.setInt(11, authorizationTimeoutSeconds);
                    insert.setString(12, now.toString());
                    insert.setString(13, now.toString());
                    insert.executeUpdate();
                }
                FareGateRow gate = new FareGateRow(
                        gateId, name, stationId, worldName,
                        blockX, blockY, blockZ, gateMode,
                        sensorJson, outputJson, authorizationTimeoutSeconds, true, 1);
                audit(connection, actorId, "FARE_GATE_CREATED", "FARE_GATE", gateId, null, gate);
                connection.commit();
                return gate;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public FareGateRow updateFareGate(
            String requestedId,
            String displayName,
            String stationIdInput,
            int blockX,
            int blockY,
            int blockZ,
            String gateModeInput,
            String sensorDefinitionJson,
            String outputDefinitionJson,
            int authorizationTimeoutSeconds,
            boolean enabled,
            int expectedVersion,
            String actorId) throws SQLException {
        String gateId = DomainId.of(requestedId).toString();
        String name = requireText(displayName, "Gate display name");
        String stationId = DomainId.of(stationIdInput).toString();
        String gateMode = requireEnum(
                gateModeInput, "Gate mode", Set.of("ENTRY", "EXIT", "BIDIRECTIONAL"));
        String sensorJson = requireJsonObject(sensorDefinitionJson, "Sensor definition");
        String outputJson = requireJsonObject(outputDefinitionJson, "Output definition");
        if (authorizationTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("Authorization timeout must be positive");
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                FareGateRow before = requireFareGate(connection, gateId);
                if (before.version() != expectedVersion) {
                    throw versionConflict("Fare gate", gateId, expectedVersion, before.version());
                }
                String worldName = requireStationWorld(connection, stationId);
                Instant now = Instant.now(clock);
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE fare_gate
                        SET display_name = ?, station_id = ?, world_name = ?,
                            block_x = ?, block_y = ?, block_z = ?, gate_mode = ?,
                            sensor_definition_json = ?, output_definition_json = ?,
                            authorization_timeout_seconds = ?, enabled = ?,
                            version = version + 1, updated_at = ?
                        WHERE id = ? AND version = ?
                        """)) {
                    update.setString(1, name);
                    update.setString(2, stationId);
                    update.setString(3, worldName);
                    update.setInt(4, blockX);
                    update.setInt(5, blockY);
                    update.setInt(6, blockZ);
                    update.setString(7, gateMode);
                    update.setString(8, sensorJson);
                    update.setString(9, outputJson);
                    update.setInt(10, authorizationTimeoutSeconds);
                    update.setInt(11, enabled ? 1 : 0);
                    update.setString(12, now.toString());
                    update.setString(13, gateId);
                    update.setInt(14, expectedVersion);
                    requireSingleUpdate(update, "Fare gate changed during update");
                }
                FareGateRow after = new FareGateRow(
                        gateId, name, stationId, worldName,
                        blockX, blockY, blockZ, gateMode,
                        sensorJson, outputJson, authorizationTimeoutSeconds,
                        enabled, expectedVersion + 1);
                audit(connection, actorId, "FARE_GATE_UPDATED", "FARE_GATE", gateId, before, after);
                connection.commit();
                return after;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public List<FareZoneRow> listFareZones() throws SQLException {
        List<FareZoneRow> rows = new ArrayList<>();
        try (Connection connection = database.openConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT id, display_name, color_rgb, version
                     FROM fare_zone
                     ORDER BY display_name, id
                     """);
             ResultSet result = query.executeQuery()) {
            while (result.next()) {
                rows.add(new FareZoneRow(
                        result.getString(1), result.getString(2),
                        result.getInt(3), result.getInt(4)));
            }
        }
        return List.copyOf(rows);
    }

    public FareZoneRow createFareZone(
            String requestedId,
            String displayName,
            int colorRgb,
            String actorId) throws SQLException {
        String zoneId = DomainId.of(requestedId).toString();
        String name = requireText(displayName, "Fare zone display name");
        requireColor(colorRgb);
        FareZoneRow zone = new FareZoneRow(zoneId, name, colorRgb, 1);
        Instant now = Instant.now(clock);
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO fare_zone(
                        id, display_name, color_rgb, version, created_at, updated_at
                    ) VALUES (?, ?, ?, 1, ?, ?)
                    """)) {
                insert.setString(1, zoneId);
                insert.setString(2, name);
                insert.setInt(3, colorRgb);
                insert.setString(4, now.toString());
                insert.setString(5, now.toString());
                insert.executeUpdate();
                audit(connection, actorId, "FARE_ZONE_CREATED", "FARE_ZONE", zoneId, null, zone);
                connection.commit();
                return zone;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public FareZoneRow updateFareZone(
            String requestedId,
            String displayName,
            int colorRgb,
            int expectedVersion,
            String actorId) throws SQLException {
        String zoneId = DomainId.of(requestedId).toString();
        String name = requireText(displayName, "Fare zone display name");
        requireColor(colorRgb);
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                FareZoneRow before = requireFareZone(connection, zoneId);
                if (before.version() != expectedVersion) {
                    throw versionConflict("Fare zone", zoneId, expectedVersion, before.version());
                }
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE fare_zone
                        SET display_name = ?, color_rgb = ?, version = version + 1, updated_at = ?
                        WHERE id = ? AND version = ?
                        """)) {
                    update.setString(1, name);
                    update.setInt(2, colorRgb);
                    update.setString(3, Instant.now(clock).toString());
                    update.setString(4, zoneId);
                    update.setInt(5, expectedVersion);
                    requireSingleUpdate(update, "Fare zone changed during update");
                }
                FareZoneRow after = new FareZoneRow(zoneId, name, colorRgb, expectedVersion + 1);
                audit(connection, actorId, "FARE_ZONE_UPDATED", "FARE_ZONE", zoneId, before, after);
                connection.commit();
                return after;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public List<FareRuleRow> listFareRules() throws SQLException {
        List<FareRuleRow> rows = new ArrayList<>();
        try (Connection connection = database.openConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT id, display_name, rule_type,
                            origin_station_id, destination_station_id,
                            origin_zone_id, destination_zone_id, line_id,
                            amount_minor, currency_code, priority,
                            bidirectional, enabled, version
                     FROM fare_rule
                     ORDER BY priority DESC, display_name, id
                     """);
             ResultSet result = query.executeQuery()) {
            while (result.next()) {
                rows.add(readFareRule(result));
            }
        }
        return List.copyOf(rows);
    }

    public FareRuleRow createFareRule(
            String requestedId,
            String displayName,
            String ruleTypeInput,
            Optional<String> originStationId,
            Optional<String> destinationStationId,
            Optional<String> originZoneId,
            Optional<String> destinationZoneId,
            Optional<String> lineId,
            int amountMinor,
            String currencyCodeInput,
            int priority,
            boolean bidirectional,
            String actorId) throws SQLException {
        String ruleId = DomainId.of(requestedId).toString();
        String name = requireText(displayName, "Fare rule display name");
        String ruleType = requireEnum(ruleTypeInput, "Fare rule type",
                Set.of("FLAT", "STATION_PAIR", "ZONE_PAIR", "DISTANCE"));
        Optional<String> originStation = cleanDomainId(originStationId);
        Optional<String> destinationStation = cleanDomainId(destinationStationId);
        Optional<String> originZone = cleanDomainId(originZoneId);
        Optional<String> destinationZone = cleanDomainId(destinationZoneId);
        Optional<String> line = cleanDomainId(lineId);
        validateFareRuleReferences(
                ruleType, originStation, destinationStation, originZone, destinationZone);
        if (amountMinor < 0) {
            throw new IllegalArgumentException("Fare amount must not be negative");
        }
        String currencyCode = requireText(currencyCodeInput, "Currency code")
                .toUpperCase(Locale.ROOT);
        if (!currencyCode.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Currency code must contain exactly three letters");
        }
        Instant now = Instant.now(clock);
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO fare_rule(
                        id, display_name, rule_type,
                        origin_station_id, destination_station_id,
                        origin_zone_id, destination_zone_id, line_id,
                        amount_minor, currency_code, priority,
                        bidirectional, enabled, version, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1, ?, ?)
                    """)) {
                insert.setString(1, ruleId);
                insert.setString(2, name);
                insert.setString(3, ruleType);
                setOptionalString(insert, 4, originStation);
                setOptionalString(insert, 5, destinationStation);
                setOptionalString(insert, 6, originZone);
                setOptionalString(insert, 7, destinationZone);
                setOptionalString(insert, 8, line);
                insert.setInt(9, amountMinor);
                insert.setString(10, currencyCode);
                insert.setInt(11, priority);
                insert.setInt(12, bidirectional ? 1 : 0);
                insert.setString(13, now.toString());
                insert.setString(14, now.toString());
                insert.executeUpdate();
                FareRuleRow rule = new FareRuleRow(
                        ruleId, name, ruleType,
                        originStation, destinationStation, originZone, destinationZone, line,
                        amountMinor, currencyCode, priority, bidirectional, true, 1);
                audit(connection, actorId, "FARE_RULE_CREATED", "FARE_RULE", ruleId, null, rule);
                connection.commit();
                return rule;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public FareRuleRow updateFareRule(
            String requestedId,
            String displayName,
            String ruleTypeInput,
            Optional<String> originStationId,
            Optional<String> destinationStationId,
            Optional<String> originZoneId,
            Optional<String> destinationZoneId,
            Optional<String> lineId,
            int amountMinor,
            String currencyCodeInput,
            int priority,
            boolean bidirectional,
            boolean enabled,
            int expectedVersion,
            String actorId) throws SQLException {
        String ruleId = DomainId.of(requestedId).toString();
        String name = requireText(displayName, "Fare rule display name");
        String ruleType = requireEnum(ruleTypeInput, "Fare rule type",
                Set.of("FLAT", "STATION_PAIR", "ZONE_PAIR", "DISTANCE"));
        Optional<String> originStation = cleanDomainId(originStationId);
        Optional<String> destinationStation = cleanDomainId(destinationStationId);
        Optional<String> originZone = cleanDomainId(originZoneId);
        Optional<String> destinationZone = cleanDomainId(destinationZoneId);
        Optional<String> line = cleanDomainId(lineId);
        validateFareRuleReferences(
                ruleType, originStation, destinationStation, originZone, destinationZone);
        if (amountMinor < 0) {
            throw new IllegalArgumentException("Fare amount must not be negative");
        }
        String currencyCode = requireText(currencyCodeInput, "Currency code")
                .toUpperCase(Locale.ROOT);
        if (!currencyCode.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Currency code must contain exactly three letters");
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                FareRuleRow before = requireFareRule(connection, ruleId);
                if (before.version() != expectedVersion) {
                    throw versionConflict("Fare rule", ruleId, expectedVersion, before.version());
                }
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE fare_rule
                        SET display_name = ?, rule_type = ?,
                            origin_station_id = ?, destination_station_id = ?,
                            origin_zone_id = ?, destination_zone_id = ?, line_id = ?,
                            amount_minor = ?, currency_code = ?, priority = ?,
                            bidirectional = ?, enabled = ?, version = version + 1, updated_at = ?
                        WHERE id = ? AND version = ?
                        """)) {
                    update.setString(1, name);
                    update.setString(2, ruleType);
                    setOptionalString(update, 3, originStation);
                    setOptionalString(update, 4, destinationStation);
                    setOptionalString(update, 5, originZone);
                    setOptionalString(update, 6, destinationZone);
                    setOptionalString(update, 7, line);
                    update.setInt(8, amountMinor);
                    update.setString(9, currencyCode);
                    update.setInt(10, priority);
                    update.setInt(11, bidirectional ? 1 : 0);
                    update.setInt(12, enabled ? 1 : 0);
                    update.setString(13, Instant.now(clock).toString());
                    update.setString(14, ruleId);
                    update.setInt(15, expectedVersion);
                    requireSingleUpdate(update, "Fare rule changed during update");
                }
                FareRuleRow after = new FareRuleRow(
                        ruleId, name, ruleType,
                        originStation, destinationStation, originZone, destinationZone, line,
                        amountMinor, currencyCode, priority, bidirectional, enabled,
                        expectedVersion + 1);
                audit(connection, actorId, "FARE_RULE_UPDATED", "FARE_RULE", ruleId, before, after);
                connection.commit();
                return after;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public List<AuditRow> listAuditRecords(int limit) throws SQLException {
        if (limit <= 0 || limit > 500) {
            throw new IllegalArgumentException("Audit limit must be between 1 and 500");
        }
        List<AuditRow> rows = new ArrayList<>();
        try (Connection connection = database.openConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT id, actor_type, actor_id, action,
                            target_type, target_id, before_json, after_json, created_at
                     FROM audit_record
                     ORDER BY created_at DESC
                     LIMIT ?
                     """)) {
            query.setInt(1, limit);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    rows.add(new AuditRow(
                            result.getString(1), result.getString(2), result.getString(3),
                            result.getString(4), result.getString(5),
                            Optional.ofNullable(result.getString(6)),
                            Optional.ofNullable(result.getString(7)),
                            Optional.ofNullable(result.getString(8)),
                            result.getString(9)));
                }
            }
        }
        return List.copyOf(rows);
    }

    public RailGraph loadRailGraph(String requestedWorld, int maximumElements) throws SQLException {
        return loadRailGraph(requestedWorld, RailGraphScope.NETWORK, maximumElements);
    }

    public RailGraph loadRailGraph(
            String requestedWorld,
            RailGraphScope scope,
            int maximumElements) throws SQLException {
        String world = requireText(requestedWorld, "Rail graph world");
        Objects.requireNonNull(scope, "scope");
        if (maximumElements <= 0) {
            throw new IllegalArgumentException("Rail graph maximum elements must be positive");
        }
        if (!worldPolicy.isActive(world)) {
            throw new IllegalArgumentException("Rail graph world is not active in TrainCarts");
        }
        RailGraphCacheLease cacheLease;
        try (Connection connection = database.openConnection()) {
            Optional<RailRunRevision> latestRun = latestRailRunRevision(connection);
            if (latestRun.isEmpty()) {
                return new RailGraph(
                        Optional.empty(), world, scope,
                        List.of(), List.of(), List.of(), List.of(),
                        0, 0, 0, 0, 0, 0, 0, false);
            }
            SignInventorySnapshot signSnapshot = loadSignInventorySnapshot(connection, world);
            RailGraphCacheKey cacheKey = new RailGraphCacheKey(
                    latestRun.orElseThrow(), world, signSnapshot.revision());
            cacheLease = acquireRailGraphCache(cacheKey);
            if (cacheLease.builder()) {
                boolean permitAcquired = false;
                try {
                    acquirePermit(sourceMaterializationPermits);
                    permitAcquired = true;
                    RailGraphSource source = loadRailGraphSource(
                            connection, cacheKey, signSnapshot.anchors());
                    railGraphMaterializations.incrementAndGet();
                    completeRailGraphSource(cacheLease, source);
                } catch (SQLException | RuntimeException exception) {
                    failRailGraphCache(cacheLease, exception);
                    throw exception;
                } finally {
                    if (permitAcquired) {
                        sourceMaterializationPermits.release();
                    }
                }
            }
        }
        RailGraphSource source = awaitRailGraphSource(cacheLease.future());
        RailGraphResponseKey responseKey = new RailGraphResponseKey(
                cacheLease.key(), scope, maximumElements);
        RailGraphResponseLease responseLease = acquireRailGraphResponseCache(responseKey);
        if (responseLease.builder()) {
            boolean permitAcquired = false;
            try {
                acquirePermit(responseRenderPermits);
                permitAcquired = true;
                RailGraphComponentSelector.Selection selection =
                        source.classification().select(scope, maximumElements);
                RailGraph graph = renderRailGraph(source, scope, selection);
                railGraphResponseRenders.incrementAndGet();
                completeRailGraphResponse(responseLease, graph);
            } catch (RuntimeException exception) {
                failRailGraphResponse(responseLease, exception);
                throw exception;
            } finally {
                if (permitAcquired) {
                    responseRenderPermits.release();
                }
            }
        }
        return awaitRailGraphResponse(responseLease.future());
    }

    private RailGraphSource loadRailGraphSource(
            Connection connection,
            RailGraphCacheKey cacheKey,
            List<RailGraphComponentSelector.Anchor> anchors) throws SQLException {
        String sourceRunId = cacheKey.run().id();
        String world = cacheKey.world();
        long sourceNodeCount = countRailSourceNodes(connection, sourceRunId, world);
        if (sourceNodeCount > maximumSourceNodes) {
            throw new RailGraphCapacityException(
                    world, "nodes", sourceNodeCount, maximumSourceNodes);
        }
        long sourceEdgeCount = countRailSourceEdges(connection, sourceRunId, world);
        if (sourceEdgeCount > maximumSourceEdges) {
            throw new RailGraphCapacityException(
                    world, "edges", sourceEdgeCount, maximumSourceEdges);
        }
        List<RailNodeSource> sourceNodes = new ArrayList<>((int) sourceNodeCount);
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT id, block_x, block_y, block_z, material_name, rail_shape
                FROM rail_candidate_node
                WHERE discovery_run_id = ? AND world_name = ?
                ORDER BY block_x, block_z, block_y
                """)) {
            query.setString(1, sourceRunId);
            query.setString(2, world);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    sourceNodes.add(new RailNodeSource(
                            result.getString(1),
                            result.getInt(2), result.getInt(3), result.getInt(4),
                            result.getString(5), result.getString(6)));
                }
            }
        }
        List<RailEdgeSource> sourceEdges = new ArrayList<>((int) sourceEdgeCount);
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT edge.first_node_id, edge.second_node_id
                FROM rail_candidate_edge edge
                JOIN rail_candidate_node first_node
                  ON first_node.id = edge.first_node_id
                JOIN rail_candidate_node second_node
                  ON second_node.id = edge.second_node_id
                WHERE edge.discovery_run_id = ?
                  AND first_node.discovery_run_id = edge.discovery_run_id
                  AND second_node.discovery_run_id = edge.discovery_run_id
                  AND first_node.world_name = ?
                  AND second_node.world_name = ?
                ORDER BY edge.first_node_id, edge.second_node_id
                """)) {
            query.setString(1, sourceRunId);
            query.setString(2, world);
            query.setString(3, world);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    sourceEdges.add(new RailEdgeSource(
                            result.getString(1), result.getString(2)));
                }
            }
        }
        List<RailGraphComponentSelector.Node> componentNodes = sourceNodes.stream()
                .map(node -> new RailGraphComponentSelector.Node(
                        node.id(), node.x(), node.y(), node.z()))
                .toList();
        List<RailGraphComponentSelector.Edge> componentEdges = sourceEdges.stream()
                .map(edge -> new RailGraphComponentSelector.Edge(
                        edge.firstNodeId(), edge.secondNodeId()))
                .toList();
        RailGraphComponentSelector.Classification classification =
                RailGraphComponentSelector.classify(componentNodes, componentEdges, anchors);
        return new RailGraphSource(
                cacheKey.run().id(), world,
                List.copyOf(sourceNodes), List.copyOf(sourceEdges), classification);
    }

    private static long countRailSourceNodes(
            Connection connection,
            String runId,
            String world) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM rail_candidate_node
                WHERE discovery_run_id = ? AND world_name = ?
                """)) {
            query.setString(1, runId);
            query.setString(2, world);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        }
    }

    private static long countRailSourceEdges(
            Connection connection,
            String runId,
            String world) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM rail_candidate_edge edge
                JOIN rail_candidate_node first_node
                  ON first_node.id = edge.first_node_id
                JOIN rail_candidate_node second_node
                  ON second_node.id = edge.second_node_id
                WHERE edge.discovery_run_id = ?
                  AND first_node.discovery_run_id = edge.discovery_run_id
                  AND second_node.discovery_run_id = edge.discovery_run_id
                  AND first_node.world_name = ?
                  AND second_node.world_name = ?
                """)) {
            query.setString(1, runId);
            query.setString(2, world);
            query.setString(3, world);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        }
    }

    private RailGraph renderRailGraph(
            RailGraphSource source,
            RailGraphScope scope,
            RailGraphComponentSelector.Selection selection) {
        List<int[]> nodes = new ArrayList<>();
        List<String> materials = new ArrayList<>();
        List<String> shapes = new ArrayList<>();
        Map<String, Integer> materialIndexes = new LinkedHashMap<>();
        Map<String, Integer> shapeIndexes = new LinkedHashMap<>();
        Map<String, Integer> responseNodeIndexes = new LinkedHashMap<>();
        for (int sourceIndex = 0; sourceIndex < source.nodes().size(); sourceIndex++) {
            if (!selection.includesNode(sourceIndex)) {
                continue;
            }
            RailNodeSource sourceNode = source.nodes().get(sourceIndex);
            int materialIndex = materialIndexes.computeIfAbsent(sourceNode.material(), key -> {
                materials.add(key);
                return materials.size() - 1;
            });
            int shapeIndex = shapeIndexes.computeIfAbsent(sourceNode.shape(), key -> {
                shapes.add(key);
                return shapes.size() - 1;
            });
            responseNodeIndexes.put(sourceNode.id(), nodes.size());
            nodes.add(new int[] {
                    sourceNode.x(), sourceNode.y(), sourceNode.z(),
                    materialIndex, shapeIndex
            });
        }
        List<int[]> edges = new ArrayList<>();
        for (RailEdgeSource sourceEdge : source.edges()) {
            Integer firstIndex = responseNodeIndexes.get(sourceEdge.firstNodeId());
            Integer secondIndex = responseNodeIndexes.get(sourceEdge.secondNodeId());
            if (firstIndex != null && secondIndex != null) {
                edges.add(new int[] {firstIndex, secondIndex});
            }
        }
        return new RailGraph(
                Optional.of(source.runId()), source.world(), scope,
                List.copyOf(materials), List.copyOf(shapes),
                List.copyOf(nodes), List.copyOf(edges),
                source.nodes().size(), source.edges().size(),
                selection.visibleComponentCount(), selection.selectedComponentCount(),
                selection.sourceComponentCount(), selection.anchoredComponentCount(),
                selection.returnedAnchoredComponentCount(),
                selection.truncated());
    }

    private SignInventorySnapshot loadSignInventorySnapshot(
            Connection connection,
            String world) throws SQLException {
        MessageDigest digest = sha256();
        List<RailGraphComponentSelector.Anchor> anchors = new ArrayList<>();
        int count = 0;
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT id, block_x, block_y, block_z
                FROM legacy_sign_inventory
                WHERE world_name = ? AND present = 1
                ORDER BY id
                """)) {
            query.setString(1, world);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    count++;
                    anchors.add(new RailGraphComponentSelector.Anchor(
                            result.getInt(2), result.getInt(3), result.getInt(4)));
                    for (int column = 1; column <= 4; column++) {
                        updateDigest(digest, result.getString(column));
                    }
                }
            }
        }
        SignInventoryRevision revision = new SignInventoryRevision(
                count, HexFormat.of().formatHex(digest.digest()));
        return new SignInventorySnapshot(revision, List.copyOf(anchors));
    }

    private RailGraphCacheLease acquireRailGraphCache(RailGraphCacheKey cacheKey) {
        synchronized (railGraphCacheLock) {
            if (newestCachedRailRun == null
                    || cacheKey.run().compareTo(newestCachedRailRun) > 0) {
                clearCompletedRailCaches();
                currentSourceKeys.clear();
                newestCachedRailRun = cacheKey.run();
            }
            boolean cacheable = cacheKey.run().equals(newestCachedRailRun);
            if (cacheable) {
                RailRunWorld runWorld = new RailRunWorld(cacheKey.run(), cacheKey.world());
                RailGraphCacheKey previousKey = currentSourceKeys.put(runWorld, cacheKey);
                if (previousKey != null && !previousKey.equals(cacheKey)) {
                    removeCompletedSource(previousKey);
                    removeCompletedResponsesForSource(previousKey);
                }
                RailGraphSource completed = completedSourceCache.get(cacheKey);
                if (completed != null) {
                    return new RailGraphCacheLease(
                            CompletableFuture.completedFuture(completed),
                            false, true, cacheKey);
                }
            }
            CompletableFuture<RailGraphSource> existing = sourceBuildsInFlight.get(cacheKey);
            if (existing != null) {
                return new RailGraphCacheLease(existing, false, cacheable, cacheKey);
            }
            CompletableFuture<RailGraphSource> future = new CompletableFuture<>();
            sourceBuildsInFlight.put(cacheKey, future);
            return new RailGraphCacheLease(future, true, cacheable, cacheKey);
        }
    }

    private void completeRailGraphSource(
            RailGraphCacheLease cacheLease,
            RailGraphSource source) {
        synchronized (railGraphCacheLock) {
            sourceBuildsInFlight.remove(cacheLease.key(), cacheLease.future());
            if (cacheLease.cacheable() && isCurrentSourceKey(cacheLease.key())) {
                addCompletedSource(cacheLease.key(), source);
            }
        }
        cacheLease.future().complete(source);
    }

    private void failRailGraphCache(RailGraphCacheLease cacheLease, Exception exception) {
        synchronized (railGraphCacheLock) {
            sourceBuildsInFlight.remove(cacheLease.key(), cacheLease.future());
        }
        cacheLease.future().completeExceptionally(exception);
    }

    private RailGraphResponseLease acquireRailGraphResponseCache(RailGraphResponseKey responseKey) {
        synchronized (railGraphCacheLock) {
            boolean cacheable = isResponseCacheable(responseKey.sourceKey());
            if (cacheable) {
                RailGraph completed = completedResponseCache.get(responseKey);
                if (completed != null) {
                    return new RailGraphResponseLease(
                            CompletableFuture.completedFuture(completed),
                            false, true, responseKey);
                }
            }
            CompletableFuture<RailGraph> existing = responseBuildsInFlight.get(responseKey);
            if (existing != null) {
                return new RailGraphResponseLease(existing, false, cacheable, responseKey);
            }
            CompletableFuture<RailGraph> future = new CompletableFuture<>();
            responseBuildsInFlight.put(responseKey, future);
            return new RailGraphResponseLease(future, true, cacheable, responseKey);
        }
    }

    private void completeRailGraphResponse(
            RailGraphResponseLease responseLease,
            RailGraph graph) {
        synchronized (railGraphCacheLock) {
            responseBuildsInFlight.remove(responseLease.key(), responseLease.future());
            if (responseLease.cacheable()
                    && isResponseCacheable(responseLease.key().sourceKey())) {
                addCompletedResponse(responseLease.key(), graph);
            }
        }
        responseLease.future().complete(graph);
    }

    private void failRailGraphResponse(
            RailGraphResponseLease responseLease,
            RuntimeException exception) {
        synchronized (railGraphCacheLock) {
            responseBuildsInFlight.remove(responseLease.key(), responseLease.future());
        }
        responseLease.future().completeExceptionally(exception);
    }

    private boolean isCurrentSourceKey(RailGraphCacheKey cacheKey) {
        return cacheKey.run().equals(newestCachedRailRun)
                && cacheKey.equals(currentSourceKeys.get(
                        new RailRunWorld(cacheKey.run(), cacheKey.world())));
    }

    private boolean isResponseCacheable(RailGraphCacheKey sourceKey) {
        return isCurrentSourceKey(sourceKey) && completedSourceCache.containsKey(sourceKey);
    }

    private void addCompletedSource(RailGraphCacheKey key, RailGraphSource source) {
        removeCompletedSource(key);
        completedSourceCache.put(key, source);
        completedSourceNodes += source.nodes().size();
        completedSourceEdges += source.edges().size();
        while (completedSourceCache.size() > MAXIMUM_COMPLETED_SOURCE_ENTRIES
                || completedSourceNodes > MAXIMUM_COMPLETED_SOURCE_NODES
                || completedSourceEdges > MAXIMUM_COMPLETED_SOURCE_EDGES) {
            Iterator<Map.Entry<RailGraphCacheKey, RailGraphSource>> iterator =
                    completedSourceCache.entrySet().iterator();
            Map.Entry<RailGraphCacheKey, RailGraphSource> eldest = iterator.next();
            iterator.remove();
            completedSourceNodes -= eldest.getValue().nodes().size();
            completedSourceEdges -= eldest.getValue().edges().size();
            removeCompletedResponsesForSource(eldest.getKey());
        }
    }

    private void removeCompletedSource(RailGraphCacheKey key) {
        RailGraphSource removed = completedSourceCache.remove(key);
        if (removed != null) {
            completedSourceNodes -= removed.nodes().size();
            completedSourceEdges -= removed.edges().size();
        }
    }

    private void addCompletedResponse(RailGraphResponseKey key, RailGraph graph) {
        removeCompletedResponse(key);
        completedResponseCache.put(key, graph);
        completedResponseNodes += graph.nodes().size();
        completedResponseEdges += graph.edges().size();
        while (completedResponseCache.size() > MAXIMUM_COMPLETED_RESPONSE_ENTRIES
                || completedResponseNodes > MAXIMUM_COMPLETED_RESPONSE_NODES
                || completedResponseEdges > MAXIMUM_COMPLETED_RESPONSE_EDGES) {
            Iterator<Map.Entry<RailGraphResponseKey, RailGraph>> iterator =
                    completedResponseCache.entrySet().iterator();
            Map.Entry<RailGraphResponseKey, RailGraph> eldest = iterator.next();
            iterator.remove();
            completedResponseNodes -= eldest.getValue().nodes().size();
            completedResponseEdges -= eldest.getValue().edges().size();
        }
    }

    private void removeCompletedResponse(RailGraphResponseKey key) {
        RailGraph removed = completedResponseCache.remove(key);
        if (removed != null) {
            completedResponseNodes -= removed.nodes().size();
            completedResponseEdges -= removed.edges().size();
        }
    }

    private void removeCompletedResponsesForSource(RailGraphCacheKey sourceKey) {
        Iterator<Map.Entry<RailGraphResponseKey, RailGraph>> iterator =
                completedResponseCache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<RailGraphResponseKey, RailGraph> entry = iterator.next();
            if (entry.getKey().sourceKey().equals(sourceKey)) {
                completedResponseNodes -= entry.getValue().nodes().size();
                completedResponseEdges -= entry.getValue().edges().size();
                iterator.remove();
            }
        }
    }

    private void clearCompletedRailCaches() {
        completedSourceCache.clear();
        completedResponseCache.clear();
        completedSourceNodes = 0;
        completedSourceEdges = 0;
        completedResponseNodes = 0;
        completedResponseEdges = 0;
    }

    private static RailGraphSource awaitRailGraphSource(
            CompletableFuture<RailGraphSource> future) throws SQLException {
        try {
            return future.join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof SQLException sqlException) {
                throw sqlException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Rail graph cache materialization failed", cause);
        }
    }

    private static RailGraph awaitRailGraphResponse(
            CompletableFuture<RailGraph> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Rail graph response render failed", cause);
        }
    }

    private static void acquirePermit(Semaphore semaphore) {
        try {
            semaphore.acquire();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Rail graph processing was interrupted", exception);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        if (value == null) {
            digest.update((byte) 0);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) 1);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    long railGraphMaterializationCount() {
        return railGraphMaterializations.get();
    }

    long railGraphResponseRenderCount() {
        return railGraphResponseRenders.get();
    }

    int railGraphCacheEntryCount() {
        synchronized (railGraphCacheLock) {
            return completedSourceCache.size();
        }
    }

    private void requireEditableSign(Connection connection, UUID signId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT world_name, present
                FROM legacy_sign_inventory
                WHERE id = ?
                """)) {
            query.setString(1, signId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Unknown legacy symbol");
                }
                if (result.getInt(2) == 0) {
                    throw new IllegalArgumentException("Legacy symbol is no longer present");
                }
                if (!worldPolicy.isActive(result.getString(1))) {
                    throw new IllegalArgumentException("Legacy symbol belongs to an inactive or archived world");
                }
            }
        }
    }

    private Optional<SymbolDecision> loadDecision(Connection connection, UUID signId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT decision, assigned_role, target_id, note, version
                FROM infrastructure_symbol_decision
                WHERE source_sign_id = ?
                """)) {
            query.setString(1, signId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new SymbolDecision(
                        result.getString(1),
                        Optional.ofNullable(result.getString(2)),
                        Optional.ofNullable(result.getString(3)),
                        Optional.ofNullable(result.getString(4)),
                        result.getInt(5)));
            }
        }
    }

    private RailRunOverview loadRailRunOverview(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT id, completed_at, discovery_mode,
                       chunks_considered, chunks_scanned, block_checks,
                       nodes_discovered, edges_discovered, node_limit_reached
                FROM rail_discovery_run
                WHERE status = 'COMPLETED'
                ORDER BY completed_at DESC
                LIMIT 1
                """);
             ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                return new RailRunOverview(
                        Optional.empty(), Optional.empty(), "NONE", 0, 0, 0, 0, 0, false);
            }
            return new RailRunOverview(
                    Optional.of(result.getString(1)),
                    Optional.of(result.getString(2)),
                    result.getString(3),
                    result.getInt(4),
                    result.getInt(5),
                    result.getLong(6),
                    result.getInt(7),
                    result.getInt(8),
                    result.getInt(9) != 0);
        }
    }

    private Optional<RailRunRevision> latestRailRunRevision(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT id, completed_at
                FROM rail_discovery_run
                WHERE status = 'COMPLETED'
                ORDER BY completed_at DESC, id DESC
                LIMIT 1
                """);
             ResultSet result = query.executeQuery()) {
            return result.next()
                    ? Optional.of(new RailRunRevision(result.getString(1), result.getString(2)))
                    : Optional.empty();
        }
    }

    private LineRow requireLine(Connection connection, String lineId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT id, display_name, color_rgb, enabled, version
                FROM line
                WHERE id = ?
                """)) {
            query.setString(1, lineId);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Unknown line: " + lineId);
                }
                return new LineRow(
                        result.getString(1), result.getString(2), result.getInt(3),
                        result.getInt(4) != 0, result.getInt(5));
            }
        }
    }

    private Optional<RoutePlanRow> loadRoutePlan(Connection connection, String routeId)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT plan.id, plan.line_id, plan.display_name,
                       plan.direction_name, plan.destination_name,
                       plan.circular, plan.anchor_station_id, plan.terminal_policy,
                       plan.track_assignment_state, plan.enabled, plan.version,
                       call.sequence_number, call.station_id, call.platform_id,
                       call.stop_policy, call.dwell_seconds
                FROM route_plan plan
                LEFT JOIN route_plan_call call ON call.route_plan_id = plan.id
                WHERE plan.id = ?
                ORDER BY call.sequence_number
                """)) {
            query.setString(1, routeId);
            try (ResultSet result = query.executeQuery()) {
                RoutePlanBuilder builder = null;
                while (result.next()) {
                    if (builder == null) {
                        builder = new RoutePlanBuilder(
                                result.getString(1), result.getString(2), result.getString(3),
                                result.getString(4), Optional.ofNullable(result.getString(5)),
                                result.getInt(6) != 0, Optional.ofNullable(result.getString(7)),
                                result.getString(8), result.getString(9),
                                result.getInt(10) != 0, result.getInt(11));
                    }
                    if (result.getObject(12) != null) {
                        builder.calls.add(new RouteCallRow(
                                result.getInt(12), result.getString(13),
                                Optional.ofNullable(result.getString(14)),
                                result.getString(15), result.getInt(16)));
                    }
                }
                return builder == null ? Optional.empty() : Optional.of(builder.freeze());
            }
        }
    }

    private static void bindRoutePlan(
            PreparedStatement statement,
            String routeId,
            String lineId,
            String displayName,
            String directionName,
            Optional<String> destinationName,
            boolean circular,
            Optional<String> anchorStationId,
            String terminalPolicy) throws SQLException {
        statement.setString(1, routeId);
        statement.setString(2, lineId);
        statement.setString(3, displayName);
        statement.setString(4, directionName);
        setOptionalString(statement, 5, destinationName);
        statement.setInt(6, circular ? 1 : 0);
        setOptionalString(statement, 7, anchorStationId);
        statement.setString(8, terminalPolicy);
    }

    private static List<RouteCallRow> normalizeRouteCalls(List<RouteCallInput> inputs) {
        List<RouteCallRow> calls = new ArrayList<>();
        for (int index = 0; index < inputs.size(); index++) {
            RouteCallInput input = Objects.requireNonNull(inputs.get(index), "Route call");
            String stationId = DomainId.of(input.stationId()).toString();
            Optional<String> platformId = cleanDomainId(Optional.ofNullable(input.platformId()));
            String stopPolicy = requireEnum(
                    input.stopPolicy(), "Stop policy",
                    Set.of("STOP", "REQUEST", "PASS", "NON_SERVICE"));
            if (input.dwellSeconds() < 0) {
                throw new IllegalArgumentException("Route call dwell must not be negative");
            }
            calls.add(new RouteCallRow(
                    index, stationId, platformId, stopPolicy, input.dwellSeconds()));
        }
        return List.copyOf(calls);
    }

    private static void insertRouteCalls(
            Connection connection,
            String routeId,
            List<RouteCallRow> calls) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO route_plan_call(
                    route_plan_id, sequence_number, station_id,
                    platform_id, stop_policy, dwell_seconds
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            for (RouteCallRow call : calls) {
                insert.setString(1, routeId);
                insert.setInt(2, call.sequenceNumber());
                insert.setString(3, call.stationId());
                setOptionalString(insert, 4, call.platformId());
                insert.setString(5, call.stopPolicy());
                insert.setInt(6, call.dwellSeconds());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private FareGateRow requireFareGate(Connection connection, String gateId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT id, display_name, station_id, world_name,
                       block_x, block_y, block_z, gate_mode,
                       sensor_definition_json, output_definition_json,
                       authorization_timeout_seconds, enabled, version
                FROM fare_gate
                WHERE id = ?
                """)) {
            query.setString(1, gateId);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Unknown fare gate: " + gateId);
                }
                return readFareGate(result);
            }
        }
    }

    private FareZoneRow requireFareZone(Connection connection, String zoneId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT id, display_name, color_rgb, version
                FROM fare_zone
                WHERE id = ?
                """)) {
            query.setString(1, zoneId);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Unknown fare zone: " + zoneId);
                }
                return new FareZoneRow(
                        result.getString(1), result.getString(2),
                        result.getInt(3), result.getInt(4));
            }
        }
    }

    private FareRuleRow requireFareRule(Connection connection, String ruleId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT id, display_name, rule_type,
                       origin_station_id, destination_station_id,
                       origin_zone_id, destination_zone_id, line_id,
                       amount_minor, currency_code, priority,
                       bidirectional, enabled, version
                FROM fare_rule
                WHERE id = ?
                """)) {
            query.setString(1, ruleId);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Unknown fare rule: " + ruleId);
                }
                return readFareRule(result);
            }
        }
    }

    private static FareGateRow readFareGate(ResultSet result) throws SQLException {
        return new FareGateRow(
                result.getString(1), result.getString(2), result.getString(3),
                result.getString(4), result.getInt(5), result.getInt(6), result.getInt(7),
                result.getString(8), result.getString(9), result.getString(10),
                result.getInt(11), result.getInt(12) != 0, result.getInt(13));
    }

    private static FareRuleRow readFareRule(ResultSet result) throws SQLException {
        return new FareRuleRow(
                result.getString(1), result.getString(2), result.getString(3),
                Optional.ofNullable(result.getString(4)),
                Optional.ofNullable(result.getString(5)),
                Optional.ofNullable(result.getString(6)),
                Optional.ofNullable(result.getString(7)),
                Optional.ofNullable(result.getString(8)),
                result.getInt(9), result.getString(10), result.getInt(11),
                result.getInt(12) != 0, result.getInt(13) != 0, result.getInt(14));
    }

    private String requireStationWorld(Connection connection, String stationId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT world_name FROM station WHERE id = ?")) {
            query.setString(1, stationId);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Unknown station: " + stationId);
                }
                String worldName = result.getString(1);
                if (!worldPolicy.isActive(worldName)) {
                    throw new IllegalArgumentException("Station belongs to an inactive or archived world");
                }
                return worldName;
            }
        }
    }

    private int count(Connection connection, String table) throws SQLException {
        if (!Set.of("station", "platform", "line", "fare_gate", "fare_rule").contains(table)) {
            throw new IllegalArgumentException("Unsupported count table");
        }
        try (PreparedStatement query = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
             ResultSet result = query.executeQuery()) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private void audit(
            Connection connection,
            String actorId,
            String action,
            String targetType,
            String targetId,
            Object before,
            Object after) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO audit_record(
                    id, correlation_id, actor_type, actor_id, action,
                    target_type, target_id, before_json, after_json, created_at
                ) VALUES (?, ?, 'WEB', ?, ?, ?, ?, ?, ?, ?)
                """)) {
            UUID correlationId = UUID.randomUUID();
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, correlationId.toString());
            insert.setString(3, actorId);
            insert.setString(4, action);
            insert.setString(5, targetType);
            insert.setString(6, targetId);
            if (before == null) {
                insert.setNull(7, Types.VARCHAR);
            } else {
                insert.setString(7, gson.toJson(before));
            }
            if (after == null) {
                insert.setNull(8, Types.VARCHAR);
            } else {
                insert.setString(8, gson.toJson(after));
            }
            insert.setString(9, Instant.now(clock).toString());
            insert.executeUpdate();
        }
    }

    private static Optional<String> cleanOptional(Optional<String> value) {
        return value.filter(item -> !item.isBlank()).map(String::strip);
    }

    private static Optional<String> cleanDomainId(Optional<String> value) {
        return cleanOptional(value).map(item -> DomainId.of(item).toString());
    }

    private static void validateFareRuleReferences(
            String ruleType,
            Optional<String> originStationId,
            Optional<String> destinationStationId,
            Optional<String> originZoneId,
            Optional<String> destinationZoneId) {
        if ("STATION_PAIR".equals(ruleType)
                && (originStationId.isEmpty() || destinationStationId.isEmpty())) {
            throw new IllegalArgumentException(
                    "A station-pair fare requires origin and destination stations");
        }
        if ("ZONE_PAIR".equals(ruleType)
                && (originZoneId.isEmpty() || destinationZoneId.isEmpty())) {
            throw new IllegalArgumentException(
                    "A zone-pair fare requires origin and destination zones");
        }
    }

    private String requireJsonObject(String value, String label) {
        String text = value == null || value.isBlank() ? "{}" : value.strip();
        JsonElement element;
        try {
            element = gson.fromJson(text, JsonElement.class);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(label + " must be valid JSON", exception);
        }
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException(label + " must be a JSON object");
        }
        return gson.toJson(element);
    }

    private static String requireEnum(String value, String label, Set<String> allowed) {
        String normalized = requireText(value, label).toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException(label + " is not supported: " + normalized);
        }
        return normalized;
    }

    private static void requireColor(int colorRgb) {
        if (colorRgb < 0 || colorRgb > 0xFFFFFF) {
            throw new IllegalArgumentException("Colour must be between #000000 and #FFFFFF");
        }
    }

    private static void requireSingleUpdate(PreparedStatement statement, String message)
            throws SQLException {
        if (statement.executeUpdate() != 1) {
            throw new OptimisticLockException(message);
        }
    }

    private static OptimisticLockException versionConflict(
            String label, String id, int expectedVersion, int currentVersion) {
        return new OptimisticLockException(
                label + " " + id + " changed from version "
                        + expectedVersion + " to " + currentVersion);
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

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        String result = value.strip();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return result;
    }

    public record Overview(
            int symbols,
            int pendingSymbols,
            int includedSymbols,
            int ignoredSymbols,
            int stations,
            int platforms,
            int lines,
            int gates,
            int fareRules,
            RailRunOverview railRun) {
    }

    public record RailRunOverview(
            Optional<String> runId,
            Optional<String> completedAt,
            String discoveryMode,
            int chunksConsidered,
            int chunksScanned,
            long blockChecks,
            int nodes,
            int edges,
            boolean nodeLimitReached) {
    }

    public record SymbolRow(
            UUID id,
            String worldName,
            int x,
            int y,
            int z,
            String side,
            String type,
            List<String> lines,
            String decision,
            Optional<String> assignedRole,
            Optional<String> targetId,
            Optional<String> note,
            int version) {
    }

    public record SymbolDecision(
            String decision,
            Optional<String> assignedRole,
            Optional<String> targetId,
            Optional<String> note,
            int version) {
    }

    public record StationRow(
            String id,
            String displayName,
            String worldName,
            boolean enabled,
            int version,
            List<PlatformRow> platforms) {
    }

    public record PlatformRow(
            String id,
            String displayName,
            int x,
            int y,
            int z,
            double berthTolerance,
            boolean enabled,
            int version) {
    }

    public record LineRow(
            String id,
            String displayName,
            int colorRgb,
            boolean enabled,
            int version) {
    }

    public record RoutePlanRow(
            String id,
            String lineId,
            String displayName,
            String directionName,
            Optional<String> destinationName,
            boolean circular,
            Optional<String> anchorStationId,
            String terminalPolicy,
            String trackAssignmentState,
            boolean enabled,
            int version,
            List<RouteCallRow> calls) {
    }

    public record RouteCallRow(
            int sequenceNumber,
            String stationId,
            Optional<String> platformId,
            String stopPolicy,
            int dwellSeconds) {
    }

    public record RouteCallInput(
            String stationId,
            String platformId,
            String stopPolicy,
            int dwellSeconds) {
    }

    public record FareGateRow(
            String id,
            String displayName,
            String stationId,
            String worldName,
            int x,
            int y,
            int z,
            String gateMode,
            String sensorDefinitionJson,
            String outputDefinitionJson,
            int authorizationTimeoutSeconds,
            boolean enabled,
            int version) {
    }

    public record FareZoneRow(
            String id,
            String displayName,
            int colorRgb,
            int version) {
    }

    public record FareRuleRow(
            String id,
            String displayName,
            String ruleType,
            Optional<String> originStationId,
            Optional<String> destinationStationId,
            Optional<String> originZoneId,
            Optional<String> destinationZoneId,
            Optional<String> lineId,
            int amountMinor,
            String currencyCode,
            int priority,
            boolean bidirectional,
            boolean enabled,
            int version) {
    }

    public record AuditRow(
            String id,
            String actorType,
            String actorId,
            String action,
            String targetType,
            Optional<String> targetId,
            Optional<String> beforeJson,
            Optional<String> afterJson,
            String createdAt) {
    }

    /**
     * visibleComponentCount is the actual response count; selectedComponentCount is the scope
     * count before capping. anchoredComponentCount describes the complete source graph, while
     * returnedAnchoredComponentCount describes only anchored components present in the response.
     * Collections are immutable; contained coordinate and edge arrays are internal read-only data.
     */
    public record RailGraph(
            Optional<String> runId,
            String worldName,
            RailGraphScope scope,
            List<String> materials,
            List<String> shapes,
            List<int[]> nodes,
            List<int[]> edges,
            int sourceNodeCount,
            int sourceEdgeCount,
            int visibleComponentCount,
            int selectedComponentCount,
            int sourceComponentCount,
            int anchoredComponentCount,
            int returnedAnchoredComponentCount,
            boolean truncated) {
    }

    private record RailNodeSource(
            String id,
            int x,
            int y,
            int z,
            String material,
            String shape) {
    }

    private record RailEdgeSource(String firstNodeId, String secondNodeId) {
    }

    private record RailGraphSource(
            String runId,
            String world,
            List<RailNodeSource> nodes,
            List<RailEdgeSource> edges,
            RailGraphComponentSelector.Classification classification) {
    }

    private record SignInventoryRevision(int presentCount, String fingerprint) {
    }

    private record SignInventorySnapshot(
            SignInventoryRevision revision,
            List<RailGraphComponentSelector.Anchor> anchors) {
    }

    private record RailGraphCacheKey(
            RailRunRevision run,
            String world,
            SignInventoryRevision signRevision) {
    }

    private record RailGraphCacheLease(
            CompletableFuture<RailGraphSource> future,
            boolean builder,
            boolean cacheable,
            RailGraphCacheKey key) {
    }

    private record RailGraphResponseKey(
            RailGraphCacheKey sourceKey,
            RailGraphScope scope,
            int maximumElements) {
    }

    private record RailGraphResponseLease(
            CompletableFuture<RailGraph> future,
            boolean builder,
            boolean cacheable,
            RailGraphResponseKey key) {
    }

    private record RailRunWorld(RailRunRevision run, String world) {
    }

    private record RailRunRevision(String id, String completedAt)
            implements Comparable<RailRunRevision> {
        @Override
        public int compareTo(RailRunRevision other) {
            int completedAtComparison = completedAt.compareTo(other.completedAt);
            return completedAtComparison != 0
                    ? completedAtComparison
                    : id.compareTo(other.id);
        }
    }

    public static final class RailGraphCapacityException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String world;
        private final String elementType;
        private final long actualCount;
        private final long maximumCount;

        private RailGraphCapacityException(
                String world,
                String elementType,
                long actualCount,
                long maximumCount) {
            super(capacityMessage(world, elementType, actualCount, maximumCount));
            this.world = world;
            this.elementType = elementType;
            this.actualCount = actualCount;
            this.maximumCount = maximumCount;
        }

        public String world() {
            return world;
        }

        public String elementType() {
            return elementType;
        }

        public long actualCount() {
            return actualCount;
        }

        public long maximumCount() {
            return maximumCount;
        }

        private static String capacityMessage(
                String world,
                String elementType,
                long actualCount,
                long maximumCount) {
            String recovery = "nodes".equals(elementType)
                    ? "Reduce infrastructure-discovery.rail.maximum-nodes to at most "
                            + maximumCount + " and run rail discovery again."
                    : "Regenerate the rail discovery inventory; report an integrity issue if "
                            + "the edge count remains above " + maximumCount + ".";
            return "Rail graph " + elementType + " for " + world + " exceed the safety limit: "
                    + actualCount + " > " + maximumCount + ". " + recovery;
        }
    }

    public static final class OptimisticLockException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public OptimisticLockException(String message) {
            super(message);
        }
    }

    private static final class StationBuilder {
        private final String id;
        private final String displayName;
        private final String worldName;
        private final boolean enabled;
        private final int version;
        private final List<PlatformRow> platforms = new ArrayList<>();

        private StationBuilder(
                String id,
                String displayName,
                String worldName,
                boolean enabled,
                int version) {
            this.id = id;
            this.displayName = displayName;
            this.worldName = worldName;
            this.enabled = enabled;
            this.version = version;
        }

        private StationRow freeze() {
            return new StationRow(id, displayName, worldName, enabled, version, List.copyOf(platforms));
        }
    }

    private static final class RoutePlanBuilder {
        private final String id;
        private final String lineId;
        private final String displayName;
        private final String directionName;
        private final Optional<String> destinationName;
        private final boolean circular;
        private final Optional<String> anchorStationId;
        private final String terminalPolicy;
        private final String trackAssignmentState;
        private final boolean enabled;
        private final int version;
        private final List<RouteCallRow> calls = new ArrayList<>();

        private RoutePlanBuilder(
                String id,
                String lineId,
                String displayName,
                String directionName,
                Optional<String> destinationName,
                boolean circular,
                Optional<String> anchorStationId,
                String terminalPolicy,
                String trackAssignmentState,
                boolean enabled,
                int version) {
            this.id = id;
            this.lineId = lineId;
            this.displayName = displayName;
            this.directionName = directionName;
            this.destinationName = destinationName;
            this.circular = circular;
            this.anchorStationId = anchorStationId;
            this.terminalPolicy = terminalPolicy;
            this.trackAssignmentState = trackAssignmentState;
            this.enabled = enabled;
            this.version = version;
        }

        private RoutePlanRow freeze() {
            return new RoutePlanRow(
                    id, lineId, displayName, directionName, destinationName,
                    circular, anchorStationId, terminalPolicy,
                    trackAssignmentState, enabled, version, List.copyOf(calls));
        }
    }
}
