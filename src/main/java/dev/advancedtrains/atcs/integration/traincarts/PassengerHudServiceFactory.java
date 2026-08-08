package dev.advancedtrains.atcs.integration.traincarts;

import dev.advancedtrains.atcs.config.PluginConfiguration;
import dev.advancedtrains.atcs.hud.NoOpPassengerHudService;
import dev.advancedtrains.atcs.hud.PassengerHudService;
import dev.advancedtrains.atcs.hud.TrainHudStateRegistry;
import dev.advancedtrains.atcs.integration.DependencyStatus;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Clock;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PassengerHudServiceFactory {
    private PassengerHudServiceFactory() {
    }

    public static PassengerHudService create(
            JavaPlugin plugin,
            PluginConfiguration.PassengerHudConfiguration configuration,
            DependencyStatus trainCarts,
            DependencyStatus bkCommonLib,
            TrainHudStateRegistry stateRegistry,
            Clock clock,
            Logger logger) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(trainCarts, "trainCarts");
        Objects.requireNonNull(bkCommonLib, "bkCommonLib");

        if (!configuration.enabled()) {
            return new NoOpPassengerHudService("disabled by configuration");
        }
        if (!trainCarts.present() || !bkCommonLib.present()) {
            return new NoOpPassengerHudService("TrainCarts or BKCommonLib is unavailable");
        }
        try {
            return new TrainCartsPassengerHudService(
                    plugin,
                    stateRegistry,
                    clock,
                    logger,
                    configuration.refreshTicks(),
                    configuration.showUnmanagedTrains());
        } catch (RuntimeException | LinkageError exception) {
            logger.log(Level.SEVERE, "TrainCarts passenger HUD could not be started", exception);
            return new NoOpPassengerHudService("TrainCarts HUD API is incompatible; see the server log");
        }
    }
}

