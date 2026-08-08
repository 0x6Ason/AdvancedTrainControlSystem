package dev.advancedtrains.atcs.integration.traincarts;

import com.bergerkiller.bukkit.tc.TrainCarts;
import dev.advancedtrains.atcs.world.RailwayWorldPolicy;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Uses TrainCarts' enabled/disabled world configuration as ATCS's sole railway-world policy.
 */
public final class TrainCartsWorldPolicy implements RailwayWorldPolicy {
    private final JavaPlugin owner;
    private final TrainCarts trainCarts;
    private final Logger logger;
    private final AtomicBoolean failureLogged = new AtomicBoolean();
    private volatile Snapshot snapshot = Snapshot.empty();

    public TrainCartsWorldPolicy(JavaPlugin owner, TrainCarts trainCarts, Logger logger) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.trainCarts = Objects.requireNonNull(trainCarts, "trainCarts");
        this.logger = Objects.requireNonNull(logger, "logger");
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("TrainCarts world policy must initialize on the Paper main thread");
        }
        refreshOnMainThread();
        owner.getServer().getScheduler().runTaskTimer(owner, this::refreshOnMainThread, 20L, 20L);
    }

    @Override
    public boolean isActive(String worldName) {
        String normalized = Objects.requireNonNull(worldName, "worldName")
                .strip()
                .toLowerCase(Locale.ROOT);
        return !normalized.isEmpty() && snapshot.normalizedNames().contains(normalized);
    }

    @Override
    public Set<String> activeWorldNames() {
        return snapshot.displayNames();
    }

    /**
     * Rechecks TrainCarts directly for a future control action. Call only on the Paper main thread.
     */
    public boolean isControlActive(World world) {
        Objects.requireNonNull(world, "world");
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("TrainCarts control policy must be checked on the Paper main thread");
        }
        try {
            return trainCarts.isEnabled() && !TrainCarts.isWorldDisabled(world);
        } catch (LinkageError | RuntimeException exception) {
            logFailure(exception);
            return false;
        }
    }

    private void refreshOnMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            snapshot = Snapshot.empty();
            logFailure(new IllegalStateException("World-policy refresh left the Paper main thread"));
            return;
        }
        try {
            if (!trainCarts.isEnabled()) {
                snapshot = Snapshot.empty();
                return;
            }
            Set<String> displayNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (World world : owner.getServer().getWorlds()) {
                if (!TrainCarts.isWorldDisabled(world)) {
                    displayNames.add(world.getName());
                }
            }
            Set<String> normalizedNames = displayNames.stream()
                    .map(name -> name.toLowerCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            snapshot = new Snapshot(Set.copyOf(displayNames), normalizedNames);
            failureLogged.set(false);
        } catch (LinkageError | RuntimeException exception) {
            snapshot = Snapshot.empty();
            logFailure(exception);
        }
    }

    private void logFailure(Throwable failure) {
        if (failureLogged.compareAndSet(false, true)) {
            logger.log(Level.SEVERE,
                    "TrainCarts world policy became unavailable; ATCS disabled all railway worlds",
                    failure);
        }
    }

    private record Snapshot(Set<String> displayNames, Set<String> normalizedNames) {
        private Snapshot {
            displayNames = Set.copyOf(displayNames);
            normalizedNames = Set.copyOf(normalizedNames);
        }

        private static Snapshot empty() {
            return new Snapshot(Set.of(), Set.of());
        }
    }
}
