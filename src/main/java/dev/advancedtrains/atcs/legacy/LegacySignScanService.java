package dev.advancedtrains.atcs.legacy;

import dev.advancedtrains.atcs.domain.BlockPosition;
import dev.advancedtrains.atcs.world.RailwayWorldPolicy;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Inventories TrainCarts signs from chunks that are already loaded, with a bounded per-tick budget.
 */
public final class LegacySignScanService implements AutoCloseable {
    private final JavaPlugin plugin;
    private final LegacySignInventoryRepository repository;
    private final LegacyReviewRepository reviewRepository;
    private final LegacySignClassifier classifier;
    private final Clock clock;
    private final Logger logger;
    private final int chunksPerTick;
    private final RailwayWorldPolicy worldPolicy;
    private final ExecutorService databaseExecutor;
    private final AtomicBoolean scanRunning = new AtomicBoolean();
    private volatile LegacyInventorySummary lastSummary;
    private volatile BukkitTask activeTask;

    public LegacySignScanService(
            JavaPlugin plugin,
            LegacySignInventoryRepository repository,
            LegacyReviewRepository reviewRepository,
            Clock clock,
            Logger logger,
            int chunksPerTick,
            RailwayWorldPolicy worldPolicy,
            LegacyInventorySummary initialSummary) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.reviewRepository = Objects.requireNonNull(reviewRepository, "reviewRepository");
        this.classifier = new LegacySignClassifier();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        if (chunksPerTick <= 0) {
            throw new IllegalArgumentException("chunksPerTick must be positive");
        }
        this.chunksPerTick = chunksPerTick;
        this.worldPolicy = Objects.requireNonNull(worldPolicy, "worldPolicy");
        this.lastSummary = Objects.requireNonNull(initialSummary, "initialSummary");
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "atcs-legacy-inventory");
            thread.setDaemon(true);
            return thread;
        };
        this.databaseExecutor = Executors.newSingleThreadExecutor(threadFactory);
    }

    public boolean startLoadedChunkScan(Consumer<LegacyScanCompletion> completion) {
        Objects.requireNonNull(completion, "completion");
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Legacy sign scanning must be started on the server thread");
        }
        if (!scanRunning.compareAndSet(false, true)) {
            return false;
        }

        List<LegacyScanChunk> targets = collectLoadedChunks();
        ScanAccumulator accumulator = new ScanAccumulator(UUID.randomUUID(), Instant.now(clock), targets);
        activeTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> processBatch(accumulator, completion), 1L, 1L);
        return true;
    }

    public boolean scanRunning() {
        return scanRunning.get();
    }

    public LegacyInventorySummary lastSummary() {
        return lastSummary;
    }

    private List<LegacyScanChunk> collectLoadedChunks() {
        List<LegacyScanChunk> chunks = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            if (!worldPolicy.isActive(world.getName())) {
                continue;
            }
            for (Chunk chunk : world.getLoadedChunks()) {
                chunks.add(new LegacyScanChunk(world.getName(), chunk.getX(), chunk.getZ()));
            }
        }
        return List.copyOf(chunks);
    }

    private void processBatch(ScanAccumulator accumulator, Consumer<LegacyScanCompletion> completion) {
        int processed = 0;
        while (processed < chunksPerTick && accumulator.hasNext()) {
            LegacyScanChunk target = accumulator.next();
            World world = Bukkit.getWorld(target.worldName());
            if (world == null || !world.isChunkLoaded(target.chunkX(), target.chunkZ())) {
                accumulator.skipChunk();
                processed++;
                continue;
            }

            Chunk chunk = world.getChunkAt(target.chunkX(), target.chunkZ());
            accumulator.scannedChunk(target);
            scanChunk(chunk, accumulator.signs());
            processed++;
        }

        if (!accumulator.hasNext()) {
            BukkitTask task = activeTask;
            if (task != null) {
                task.cancel();
                activeTask = null;
            }
            persistScan(accumulator, completion);
        }
    }

    private void scanChunk(Chunk chunk, List<LegacyTrainCartsSign> signs) {
        LegacyComponentSerializer serializer = LegacyComponentSerializer.legacySection();
        for (BlockState state : chunk.getTileEntities()) {
            if (!(state instanceof Sign sign)) {
                continue;
            }
            BlockPosition position = new BlockPosition(
                    chunk.getWorld().getName(), sign.getX(), sign.getY(), sign.getZ());
            for (Side side : Side.values()) {
                List<String> lines = sign.getSide(side).lines().stream()
                        .map(serializer::serialize)
                        .toList();
                classifier.classify(position, side.name(), lines).ifPresent(signs::add);
            }
        }
    }

    private void persistScan(ScanAccumulator accumulator, Consumer<LegacyScanCompletion> completion) {
        LegacyScanResult result = accumulator.complete(Instant.now(clock));
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        repository.storeCompletedScan(result);
                        reviewRepository.refresh();
                        return repository.loadSummary();
                    } catch (SQLException exception) {
                        throw new ScanPersistenceException(exception);
                    }
                }, databaseExecutor)
                .whenComplete((summary, error) -> {
                    Runnable callback = () -> {
                        scanRunning.set(false);
                        if (error == null) {
                            lastSummary = summary;
                            completion.accept(new LegacyScanCompletion(
                                    true,
                                    "Loaded-chunk sign inventory completed.",
                                    Optional.of(result),
                                    summary));
                        } else {
                            logger.log(Level.SEVERE, "Could not persist the legacy sign inventory", error);
                            completion.accept(new LegacyScanCompletion(
                                    false,
                                    "The scan completed, but its database transaction failed.",
                                    Optional.of(result),
                                    lastSummary));
                        }
                    };
                    if (plugin.isEnabled()) {
                        Bukkit.getScheduler().runTask(plugin, callback);
                    } else {
                        scanRunning.set(false);
                    }
                });
    }

    @Override
    public void close() {
        BukkitTask task = activeTask;
        if (task != null) {
            task.cancel();
            activeTask = null;
        }
        scanRunning.set(false);
        databaseExecutor.shutdown();
        try {
            if (!databaseExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                databaseExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            databaseExecutor.shutdownNow();
        }
    }

    private static final class ScanAccumulator {
        private final UUID runId;
        private final Instant startedAt;
        private final List<LegacyScanChunk> targets;
        private final List<LegacyScanChunk> scannedChunks = new ArrayList<>();
        private final List<LegacyTrainCartsSign> signs = new ArrayList<>();
        private int index;
        private int skippedChunks;

        private ScanAccumulator(UUID runId, Instant startedAt, List<LegacyScanChunk> targets) {
            this.runId = runId;
            this.startedAt = startedAt;
            this.targets = targets;
        }

        private boolean hasNext() {
            return index < targets.size();
        }

        private LegacyScanChunk next() {
            return targets.get(index++);
        }

        private void scannedChunk(LegacyScanChunk chunk) {
            scannedChunks.add(chunk);
        }

        private void skipChunk() {
            skippedChunks++;
        }

        private List<LegacyTrainCartsSign> signs() {
            return signs;
        }

        private LegacyScanResult complete(Instant completedAt) {
            return new LegacyScanResult(
                    runId, startedAt, completedAt, scannedChunks, skippedChunks, signs);
        }
    }

    private static final class ScanPersistenceException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private ScanPersistenceException(Throwable cause) {
            super(cause);
        }
    }
}
