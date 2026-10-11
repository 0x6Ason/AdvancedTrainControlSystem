package dev.advancedtrains.atcs.runtime;

import dev.advancedtrains.atcs.operation.TrainInfo;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Optional;

/** Access to the trains currently loaded on the server. */
public interface TrainSource {

    Collection<TrainHandle> loadedTrains();

    /** Every train TrainCarts knows, loaded or not, as far as its stored properties go. */
    Collection<TrainInfo> allTrains();

    boolean nameTaken(String name);

    Optional<TrainHandle> train(String name);

    /** The train the player is riding, if any. */
    Optional<TrainHandle> trainOf(Player player);

    /** The train whose cart the player is looking at within a few blocks, if any. */
    Optional<TrainHandle> trainLookedAt(Player player);
}
