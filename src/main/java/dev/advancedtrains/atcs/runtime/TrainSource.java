package dev.advancedtrains.atcs.runtime;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Optional;

/** Access to the trains currently loaded on the server. */
public interface TrainSource {

    Collection<TrainHandle> loadedTrains();

    Optional<TrainHandle> train(String name);

    /** The train the player is riding, if any. */
    Optional<TrainHandle> trainOf(Player player);

    /** The train whose cart the player is looking at within a few blocks, if any. */
    Optional<TrainHandle> trainLookedAt(Player player);
}
