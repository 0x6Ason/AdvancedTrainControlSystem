package dev.advancedtrains.atcs.discovery;

import dev.advancedtrains.atcs.config.InfrastructureDiscoveryConfiguration;
import dev.advancedtrains.atcs.domain.BlockPosition;
import dev.advancedtrains.atcs.world.RailwayWorldPolicy;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rail;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Inventories every rail block in the configured active-world scope.
 * Signs are independent evidence overlays and never seed or limit this scan.
 */
public final class RailDiscoveryService implements AutoCloseable {
    private final JavaPlugin plugin;
    private final RailCandidateRepository repository;
    private final InfrastructureDiscoveryConfiguration.Rail configuration;
    private final RailwayWorldPolicy worldPolicy;
    private final Clock clock;
    private final Logger logger;
    private final ExecutorService databaseExecutor;
    private final ExecutorService preparationExecutor;
    private final SavedRailChunkLocator savedChunkLocator = new SavedRailChunkLocator();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile RailDiscoverySummary lastSummary;
    private volatile BukkitTask activeTask;

    public RailDiscoveryService(
            JavaPlugin plugin,
            RailCandidateRepository repository,
            InfrastructureDiscoveryConfiguration.Rail configuration,
            RailwayWorldPolicy worldPolicy,
            RailDiscoverySummary initialSummary,
            Clock clock,
            Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.worldPolicy = Objects.requireNonNull(worldPolicy, "worldPolicy");
        this.lastSummary = Objects.requireNonNull(initialSummary, "initialSummary");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "atcs-rail-discovery-store");
            thread.setDaemon(true);
            return thread;
        };
        this.databaseExecutor = Executors.newSingleThreadExecutor(threadFactory);
        ThreadFactory preparationThreadFactory = runnable -> {
            Thread thread = new Thread(runnable, "atcs-saved-rail-index");
            thread.setDaemon(true);
            return thread;
        };
        this.preparationExecutor = Executors.newSingleThreadExecutor(preparationThreadFactory);
    }

    public boolean startRailDiscovery(Consumer<RailDiscoveryCompletion> completion) {
        Objects.requireNonNull(completion, "completion");
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Rail discovery must be started on the server thread");
        }
        if (!running.compareAndSet(false, true)) {
            return false;
        }

        List<WorldScanDescriptor> worlds = Bukkit.getWorlds().stream()
                .filter(world -> worldPolicy.isActive(world.getName()))
                .map(world -> new WorldScanDescriptor(
                        world.getName(),
                        world.getWorldFolder().toPath().toAbsolutePath().normalize(),
                        world.getMinHeight(),
                        world.getMaxHeight()))
                .sorted(Comparator.comparing(WorldScanDescriptor::worldName))
                .toList();
        if (configuration.scope() == InfrastructureDiscoveryConfiguration.Rail.Scope.SAVED_CHUNKS) {
            CompletableFuture
                    .supplyAsync(() -> locateSavedRailTargets(worlds), preparationExecutor)
                    .whenComplete((targets, error) -> {
                        if (!plugin.isEnabled()) {
                            running.set(false);
                            return;
                        }
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (error == null) {
                                beginScan(targets, "ALL_SAVED_RAIL_CHUNKS", completion);
                            } else {
                                failPreparation(error, completion);
                            }
                        });
                    });
        } else {
            beginScan(loadedChunkTargets(worlds), "ALL_LOADED_CHUNKS", completion);
        }
        return true;
    }

    public InfrastructureDiscoveryConfiguration.Rail.Scope scope() {
        return configuration.scope();
    }

    private List<ChunkTarget> locateSavedRailTargets(List<WorldScanDescriptor> worlds) {
        try {
            List<ChunkTarget> targets = new ArrayList<>();
            for (WorldScanDescriptor world : worlds) {
                for (SavedRailChunkLocator.LocatedChunk locatedChunk :
                        savedChunkLocator.locate(world.worldDirectory())) {
                    SavedRailChunkLocator.ChunkCoordinate coordinate = locatedChunk.coordinate();
                    List<FoundRail> directRails = locatedChunk.directRails().stream()
                            .map(rail -> new FoundRail(
                                    new BlockPosition(
                                            world.worldName(),
                                            rail.x(),
                                            rail.y(),
                                            rail.z()),
                                    rail.materialName(),
                                    rail.shape()))
                            .toList();
                    targets.add(new ChunkTarget(
                            world.worldName(),
                            coordinate.x(),
                            coordinate.z(),
                            world.minHeight(),
                            world.maxHeight(),
                            locatedChunk.loadWithBukkit(),
                            !locatedChunk.loadWithBukkit(),
                            directRails,
                            locatedChunk.directBlockChecks()));
                }
            }
            return sortTargets(targets);
        } catch (IOException exception) {
            throw new RailDiscoveryPreparationException(exception);
        }
    }

    private List<ChunkTarget> loadedChunkTargets(List<WorldScanDescriptor> descriptors) {
        Map<String, WorldScanDescriptor> byName = new LinkedHashMap<>();
        for (WorldScanDescriptor descriptor : descriptors) {
            byName.put(descriptor.worldName(), descriptor);
        }
        List<ChunkTarget> targets = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            WorldScanDescriptor descriptor = byName.get(world.getName());
            if (descriptor == null) {
                continue;
            }
            for (Chunk chunk : world.getLoadedChunks()) {
                targets.add(new ChunkTarget(
                        world.getName(),
                        chunk.getX(),
                        chunk.getZ(),
                        descriptor.minHeight(),
                        descriptor.maxHeight(),
                        false,
                        false,
                        List.of(),
                        0));
            }
        }
        return sortTargets(targets);
    }

    private List<ChunkTarget> sortTargets(List<ChunkTarget> targets) {
        targets.sort(Comparator.comparing(ChunkTarget::worldName)
                .thenComparingInt(ChunkTarget::chunkX)
                .thenComparingInt(ChunkTarget::chunkZ));
        return List.copyOf(targets);
    }

    private void beginScan(
            List<ChunkTarget> targets,
            String discoveryMode,
            Consumer<RailDiscoveryCompletion> completion) {
        if (!plugin.isEnabled()) {
            running.set(false);
            return;
        }
        ScanAccumulator accumulator = new ScanAccumulator(
                UUID.randomUUID(), Instant.now(clock), targets, discoveryMode);
        activeTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                () -> processTickSafely(accumulator, completion),
                1L,
                1L);
    }

    private void failPreparation(
            Throwable error,
            Consumer<RailDiscoveryCompletion> completion) {
        running.set(false);
        logger.log(Level.SEVERE, "Could not index saved chunks for rail discovery", error);
        completion.accept(new RailDiscoveryCompletion(
                false,
                "Saved-chunk rail indexing failed. Check the server log.",
                lastSummary));
    }

    public boolean running() {
        return running.get();
    }

    public RailDiscoverySummary lastSummary() {
        return lastSummary;
    }

    private void processTickSafely(
            ScanAccumulator accumulator,
            Consumer<RailDiscoveryCompletion> completion) {
        try {
            processTick(accumulator, completion);
        } catch (RuntimeException exception) {
            BukkitTask task = activeTask;
            if (task != null) {
                task.cancel();
                activeTask = null;
            }
            running.set(false);
            logger.log(Level.SEVERE, "Rail inventory failed", exception);
            completion.accept(new RailDiscoveryCompletion(
                    false,
                    "Rail inventory failed. Check the server log.",
                    lastSummary));
        }
    }

    private void processTick(
            ScanAccumulator accumulator,
            Consumer<RailDiscoveryCompletion> completion) {
        if (accumulator.phase == ScanPhase.SCANNING) {
            scanChunks(accumulator);
            return;
        }
        if (accumulator.phase == ScanPhase.LINKING) {
            int processed = 0;
            while (processed < configuration.edgeNodesPerTick()
                    && accumulator.hasUnlinkedNodes()) {
                linkNode(accumulator, accumulator.nextUnlinkedNode());
                processed++;
            }
            if (!accumulator.hasUnlinkedNodes()) {
                finishAndPersist(accumulator, completion);
            }
        }
    }

    private void scanChunks(ScanAccumulator accumulator) {
        int checksRemaining = configuration.blockChecksPerTick();
        int snapshotsRemaining = configuration.chunkSnapshotsPerTick();

        while (checksRemaining > 0 && accumulator.phase == ScanPhase.SCANNING) {
            if (accumulator.cursor == null) {
                if (!accumulator.hasUnscannedTargets()) {
                    accumulator.startLinking();
                    return;
                }
                if (snapshotsRemaining == 0) {
                    return;
                }
                ChunkTarget target = accumulator.nextTarget();
                snapshotsRemaining--;
                if (target.directRead()) {
                    accumulator.blockChecks += target.directBlockChecks();
                    for (FoundRail rail : target.directRails()) {
                        addRail(accumulator, rail);
                        if (accumulator.phase != ScanPhase.SCANNING) {
                            break;
                        }
                    }
                    accumulator.chunksScanned++;
                    if (accumulator.phase != ScanPhase.SCANNING) {
                        return;
                    }
                    continue;
                }
                Optional<ChunkSnapshot> snapshot = captureSnapshot(target);
                if (snapshot.isEmpty()) {
                    accumulator.unloadedBoundaries.add(target.boundary());
                    continue;
                }
                accumulator.cursor = new SnapshotCursor(
                        snapshot.orElseThrow(),
                        target.minHeight(),
                        target.maxHeight());
            }

            ScanStep step = accumulator.cursor.next();
            if (step == null) {
                accumulator.chunksScanned++;
                accumulator.cursor = null;
                continue;
            }
            checksRemaining--;
            accumulator.blockChecks++;
            if (step.rail().isPresent()) {
                addRail(accumulator, step.rail().orElseThrow());
            }
        }
    }

    private Optional<ChunkSnapshot> captureSnapshot(ChunkTarget target) {
        World world = Bukkit.getWorld(target.worldName());
        if (world == null) {
            return Optional.empty();
        }
        boolean alreadyLoaded = world.isChunkLoaded(target.chunkX(), target.chunkZ());
        if (!alreadyLoaded && !target.loadSavedChunk()) {
            return Optional.empty();
        }
        if (!alreadyLoaded && !world.loadChunk(target.chunkX(), target.chunkZ(), false)) {
            return Optional.empty();
        }
        try {
            Chunk chunk = world.getChunkAt(target.chunkX(), target.chunkZ());
            return Optional.of(chunk.getChunkSnapshot(false, false, false));
        } finally {
            if (!alreadyLoaded && target.loadSavedChunk()) {
                world.unloadChunkRequest(target.chunkX(), target.chunkZ());
            }
        }
    }

    private void addRail(ScanAccumulator accumulator, FoundRail rail) {
        if (accumulator.nodes.containsKey(rail.position())) {
            return;
        }
        if (accumulator.nodes.size() >= configuration.maximumNodes()) {
            accumulator.nodeLimitReached = true;
            accumulator.startLinking();
            return;
        }
        RailCandidateSnapshot.Node node = new RailCandidateSnapshot.Node(
                stableNodeId(accumulator.runId, rail.position()),
                rail.position(),
                rail.materialName(),
                rail.shape());
        accumulator.nodes.put(rail.position(), node);
    }

    private void linkNode(
            ScanAccumulator accumulator,
            RailCandidateSnapshot.Node current) {
        BlockPosition position = current.position();
        for (RailGeometry.Edge shapeEdge : RailGeometry.edges(current.shape())) {
            int neighborX = position.x() + shapeEdge.direction().deltaX();
            int neighborZ = position.z() + shapeEdge.direction().deltaZ();
            ChunkBoundary boundary = new ChunkBoundary(
                    position.worldName(),
                    Math.floorDiv(neighborX, 16),
                    Math.floorDiv(neighborZ, 16));
            if (!accumulator.consideredChunks.contains(boundary)) {
                accumulator.unloadedBoundaries.add(boundary);
                continue;
            }
            int connectorY = position.y() + shapeEdge.height();
            for (int neighborY : List.of(connectorY, connectorY - 1)) {
                BlockPosition neighborPosition = new BlockPosition(
                        position.worldName(), neighborX, neighborY, neighborZ);
                RailCandidateSnapshot.Node neighbor = accumulator.nodes.get(neighborPosition);
                if (neighbor == null || !RailGeometry.connects(
                        current.shape(),
                        position.y(),
                        neighbor.shape(),
                        neighborY,
                        shapeEdge.direction())) {
                    continue;
                }
                accumulator.edges.add(RailCandidateSnapshot.Edge.of(
                        current.id(), neighbor.id()));
            }
        }
    }

    private void finishAndPersist(
            ScanAccumulator accumulator,
            Consumer<RailDiscoveryCompletion> completion) {
        BukkitTask task = activeTask;
        if (task != null) {
            task.cancel();
            activeTask = null;
        }
        accumulator.phase = ScanPhase.PERSISTING;
        RailCandidateSnapshot snapshot = accumulator.complete(Instant.now(clock));
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        repository.store(snapshot);
                        return RailDiscoverySummary.from(snapshot);
                    } catch (SQLException exception) {
                        throw new RailDiscoveryPersistenceException(exception);
                    }
                }, databaseExecutor)
                .whenComplete((summary, error) -> complete(summary, error, completion));
    }

    private void complete(
            RailDiscoverySummary summary,
            Throwable error,
            Consumer<RailDiscoveryCompletion> completion) {
        Runnable callback = () -> {
            running.set(false);
            if (error == null) {
                lastSummary = summary;
                completion.accept(new RailDiscoveryCompletion(
                        true,
                        "Rail inventory completed for the configured world scope.",
                        summary));
            } else {
                logger.log(Level.SEVERE, "Could not persist the rail inventory", error);
                completion.accept(new RailDiscoveryCompletion(
                        false,
                        "Rail inventory failed. Check the server log.",
                        lastSummary));
            }
        };
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, callback);
        } else {
            running.set(false);
        }
    }

    private static UUID stableNodeId(UUID runId, BlockPosition position) {
        String identity = String.join(":",
                runId.toString(),
                position.worldName(),
                Integer.toString(position.x()),
                Integer.toString(position.y()),
                Integer.toString(position.z()));
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        BukkitTask task = activeTask;
        if (task != null) {
            task.cancel();
            activeTask = null;
        }
        running.set(false);
        databaseExecutor.shutdown();
        preparationExecutor.shutdown();
        try {
            boolean databaseStopped = databaseExecutor.awaitTermination(2, TimeUnit.SECONDS);
            boolean preparationStopped = preparationExecutor.awaitTermination(2, TimeUnit.SECONDS);
            if (!databaseStopped) {
                databaseExecutor.shutdownNow();
            }
            if (!preparationStopped) {
                preparationExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            databaseExecutor.shutdownNow();
            preparationExecutor.shutdownNow();
        }
    }

    private enum ScanPhase {
        SCANNING,
        LINKING,
        PERSISTING
    }

    private record ChunkTarget(
            String worldName,
            int chunkX,
            int chunkZ,
            int minHeight,
            int maxHeight,
            boolean loadSavedChunk,
            boolean directRead,
            List<FoundRail> directRails,
            long directBlockChecks) {
        private ChunkTarget {
            directRails = List.copyOf(directRails);
            if (directRead && loadSavedChunk) {
                throw new IllegalArgumentException("A chunk target cannot use two read strategies");
            }
            if (!directRead && !directRails.isEmpty()) {
                throw new IllegalArgumentException("Direct rails require direct chunk reading");
            }
            if (directBlockChecks < 0) {
                throw new IllegalArgumentException("Direct block checks cannot be negative");
            }
        }

        private ChunkBoundary boundary() {
            return new ChunkBoundary(worldName, chunkX, chunkZ);
        }
    }

    private record WorldScanDescriptor(
            String worldName,
            Path worldDirectory,
            int minHeight,
            int maxHeight) {
    }

    private record ChunkBoundary(String worldName, int chunkX, int chunkZ) {
    }

    private record FoundRail(
            BlockPosition position,
            String materialName,
            Rail.Shape shape) {
    }

    private record ScanStep(Optional<FoundRail> rail) {
    }

    private static final class SnapshotCursor {
        private final ChunkSnapshot snapshot;
        private final int minSection;
        private final int maxSectionExclusive;
        private int sectionY;
        private int localX;
        private int localY;
        private int localZ;
        private boolean sectionReady;

        private SnapshotCursor(ChunkSnapshot snapshot, int minHeight, int maxHeight) {
            this.snapshot = snapshot;
            this.minSection = Math.floorDiv(minHeight, 16);
            this.maxSectionExclusive = Math.floorDiv(maxHeight - 1, 16) + 1;
            this.sectionY = minSection;
        }

        private ScanStep next() {
            while (sectionY < maxSectionExclusive) {
                if (!sectionReady) {
                    if (snapshot.isSectionEmpty(sectionY - minSection)) {
                        sectionY++;
                        continue;
                    }
                    sectionReady = true;
                }
                int blockY = sectionY * 16 + localY;
                BlockData blockData = snapshot.getBlockData(localX, blockY, localZ);
                int blockX = snapshot.getX() * 16 + localX;
                int blockZ = snapshot.getZ() * 16 + localZ;
                advance();
                if (blockData instanceof Rail rail) {
                    return new ScanStep(Optional.of(new FoundRail(
                            new BlockPosition(snapshot.getWorldName(), blockX, blockY, blockZ),
                            blockData.getMaterial().getKey().asString(),
                            rail.getShape())));
                }
                return new ScanStep(Optional.empty());
            }
            return null;
        }

        private void advance() {
            localX++;
            if (localX < 16) {
                return;
            }
            localX = 0;
            localZ++;
            if (localZ < 16) {
                return;
            }
            localZ = 0;
            localY++;
            if (localY < 16) {
                return;
            }
            localY = 0;
            sectionY++;
            sectionReady = false;
        }
    }

    private static final class ScanAccumulator {
        private final UUID runId;
        private final Instant startedAt;
        private final List<ChunkTarget> targets;
        private final String discoveryMode;
        private final Set<ChunkBoundary> consideredChunks;
        private final Map<BlockPosition, RailCandidateSnapshot.Node> nodes = new LinkedHashMap<>();
        private final Set<RailCandidateSnapshot.Edge> edges = new LinkedHashSet<>();
        private final Set<ChunkBoundary> unloadedBoundaries = new LinkedHashSet<>();
        private ScanPhase phase = ScanPhase.SCANNING;
        private SnapshotCursor cursor;
        private List<RailCandidateSnapshot.Node> linkOrder = List.of();
        private int targetIndex;
        private int linkIndex;
        private int chunksScanned;
        private long blockChecks;
        private boolean nodeLimitReached;

        private ScanAccumulator(
                UUID runId,
                Instant startedAt,
                List<ChunkTarget> targets,
                String discoveryMode) {
            this.runId = runId;
            this.startedAt = startedAt;
            this.targets = List.copyOf(targets);
            this.discoveryMode = discoveryMode;
            Set<ChunkBoundary> chunks = new LinkedHashSet<>();
            for (ChunkTarget target : targets) {
                chunks.add(target.boundary());
            }
            this.consideredChunks = Set.copyOf(chunks);
        }

        private boolean hasUnscannedTargets() {
            return targetIndex < targets.size();
        }

        private ChunkTarget nextTarget() {
            return targets.get(targetIndex++);
        }

        private void startLinking() {
            phase = ScanPhase.LINKING;
            cursor = null;
            linkOrder = List.copyOf(nodes.values());
        }

        private boolean hasUnlinkedNodes() {
            return linkIndex < linkOrder.size();
        }

        private RailCandidateSnapshot.Node nextUnlinkedNode() {
            return linkOrder.get(linkIndex++);
        }

        private RailCandidateSnapshot complete(Instant completedAt) {
            List<RailCandidateSnapshot.Node> orderedNodes = nodes.values().stream()
                    .sorted(Comparator.comparing((RailCandidateSnapshot.Node node) ->
                                    node.position().worldName())
                            .thenComparingInt(node -> node.position().x())
                            .thenComparingInt(node -> node.position().y())
                            .thenComparingInt(node -> node.position().z()))
                    .toList();
            List<RailCandidateSnapshot.Edge> orderedEdges = edges.stream()
                    .sorted(Comparator
                            .comparing((RailCandidateSnapshot.Edge edge) ->
                                    edge.firstNodeId().toString())
                            .thenComparing(edge -> edge.secondNodeId().toString()))
                    .toList();
            return new RailCandidateSnapshot(
                    runId,
                    startedAt,
                    completedAt,
                    orderedNodes,
                    orderedEdges,
                    discoveryMode,
                    targets.size(),
                    chunksScanned,
                    blockChecks,
                    unloadedBoundaries.size(),
                    nodeLimitReached);
        }
    }

    private static final class RailDiscoveryPersistenceException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private RailDiscoveryPersistenceException(Throwable cause) {
            super(cause);
        }
    }

    private static final class RailDiscoveryPreparationException extends CompletionException {
        private static final long serialVersionUID = 1L;

        private RailDiscoveryPreparationException(Throwable cause) {
            super(cause);
        }
    }
}
