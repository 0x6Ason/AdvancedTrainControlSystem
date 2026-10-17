package dev.advancedtrains.atcs.runtime;

import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.operation.TrainInfo;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

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

    /**
     * One position in each chunk a stored train occupies, for a train that is not loaded; empty
     * for a loaded or unknown train, or one in a world that is not loaded.
     */
    default List<BlockPos> storedChunks(String name) {
        return List.of();
    }

    /**
     * Sends standing trains on their way again that have nothing to move them, by giving the ATCS
     * sign they stand on the train afresh. Returns the names of the trains sent on.
     *
     * @param wanted             which trains qualify (those on a service)
     * @param standingLongEnough whether a train has stood long enough to count as stranded
     */
    default List<String> relaunchStranded(Predicate<TrainHandle> wanted, Predicate<String> standingLongEnough) {
        return List.of();
    }
}
