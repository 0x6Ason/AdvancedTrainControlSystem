package dev.advancedtrains.atcs.integration.traincarts;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartGroupStore;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import dev.advancedtrains.atcs.hud.PassengerHudRenderer;
import dev.advancedtrains.atcs.hud.PassengerHudService;
import dev.advancedtrains.atcs.hud.PassengerHudSnapshot;
import dev.advancedtrains.atcs.hud.HudTagMetadata;
import dev.advancedtrains.atcs.hud.TrainHudStateRegistry;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Refreshes the passenger action bar from live TrainCarts properties without changing train state.
 */
public final class TrainCartsPassengerHudService implements PassengerHudService {
    private final TrainHudStateRegistry stateRegistry;
    private final PassengerHudRenderer renderer = new PassengerHudRenderer();
    private final Clock clock;
    private final Logger logger;
    private final boolean showUnmanagedTrains;
    private final AtomicBoolean failureLogged = new AtomicBoolean();
    private final BukkitTask task;

    public TrainCartsPassengerHudService(
            JavaPlugin plugin,
            TrainHudStateRegistry stateRegistry,
            Clock clock,
            Logger logger,
            int refreshTicks,
            boolean showUnmanagedTrains) {
        this.stateRegistry = Objects.requireNonNull(stateRegistry, "stateRegistry");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.showUnmanagedTrains = showUnmanagedTrains;
        this.task = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::refreshAllPassengers, refreshTicks, refreshTicks);
    }

    private void refreshAllPassengers() {
        try {
            MinecartGroupStore.getGroups().forEach(this::refreshGroupPassengers);
            failureLogged.set(false);
        } catch (RuntimeException | LinkageError exception) {
            if (failureLogged.compareAndSet(false, true)) {
                logger.log(Level.SEVERE, "Passenger HUD refresh failed; further duplicate errors are suppressed", exception);
            }
        }
    }

    private void refreshGroupPassengers(MinecartGroup group) {
        TrainProperties properties = group.getProperties();
        HudTagMetadata tags = HudTagMetadata.parse(properties.getTags());
        if (!showUnmanagedTrains && tags.line().isEmpty()) {
            return;
        }

        String trainName = properties.getTrainName();
        String currentDestination = tags.nextStation()
                .orElseGet(() -> defaultText(properties.getDestination(), "Not set"));
        List<String> route = properties.getDestinationRoute();
        String finalDestination = tags.destination().orElseGet(() -> route.isEmpty()
                ? currentDestination
                : defaultText(route.get(route.size() - 1), currentDestination));
        String line = tags.line().orElseGet(() -> defaultLineName(properties));
        String serviceType = tags.serviceType().orElse("Train");
        String status = stateRegistry.statusFor(trainName, Instant.now(clock)).orElse("In service");
        PassengerHudSnapshot snapshot = new PassengerHudSnapshot(
                line, serviceType, finalDestination, currentDestination, status);

        group.forEach(member -> {
            for (Player passenger : member.getEntity().getPlayerPassengers()) {
                if (passenger.hasPermission("atcs.hud")) {
                    passenger.sendActionBar(renderer.render(snapshot));
                }
            }
        });
    }

    private String defaultLineName(TrainProperties properties) {
        String displayName = properties.getDisplayName();
        if (displayName.equals(properties.getTrainName())) {
            return "Rail Service";
        }
        return defaultText(displayName, "Rail Service");
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    @Override
    public boolean running() {
        return !task.isCancelled();
    }

    @Override
    public String statusMessage() {
        return running() ? "continuous passenger action bar is running" : "passenger action bar is stopped";
    }

    @Override
    public void close() {
        task.cancel();
    }

}
