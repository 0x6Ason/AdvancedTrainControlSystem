package dev.advancedtrains.atcs.integration.traincarts;

import dev.advancedtrains.atcs.integration.DependencyStatus;
import dev.advancedtrains.atcs.receiver.NoOpReceiverRegistrar;
import dev.advancedtrains.atcs.receiver.ReceiverEventService;
import dev.advancedtrains.atcs.receiver.ReceiverRegistrar;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ReceiverRegistrarFactory {
    private ReceiverRegistrarFactory() {
    }

    public static ReceiverRegistrar create(
            boolean enabled,
            DependencyStatus trainCarts,
            DependencyStatus bkCommonLib,
            ReceiverEventService receiverEvents,
            Logger logger) {
        Objects.requireNonNull(trainCarts, "trainCarts");
        Objects.requireNonNull(bkCommonLib, "bkCommonLib");
        Objects.requireNonNull(receiverEvents, "receiverEvents");
        Objects.requireNonNull(logger, "logger");

        if (!enabled) {
            return new NoOpReceiverRegistrar("disabled by configuration");
        }
        if (!bkCommonLib.present()) {
            return new NoOpReceiverRegistrar("BKCommonLib is unavailable");
        }
        if (!trainCarts.present()) {
            return new NoOpReceiverRegistrar("Train_Carts is unavailable");
        }

        try {
            return new TrainCartsReceiverRegistrar(receiverEvents);
        } catch (LinkageError | RuntimeException exception) {
            logger.log(Level.SEVERE, "TrainCarts receiver adapter could not be registered", exception);
            return new NoOpReceiverRegistrar("TrainCarts API is incompatible; see the server log");
        }
    }
}

