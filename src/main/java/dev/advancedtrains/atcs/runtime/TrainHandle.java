package dev.advancedtrains.atcs.runtime;

import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.operation.TrainInfo;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.List;

/** A loaded train, as seen by ATCS. Implemented by the TrainCarts adapter. */
public interface TrainHandle {

    String name();

    TrainInfo info();

    /** Position of the front cart, or {@code null} when it cannot be determined. */
    @Nullable Location headLocation();

    /** How fast the train moves, in blocks per tick; 0 when standing. */
    double speed();

    List<Player> riders();

    /** Stores the service on the train as a TrainCarts tag, so it survives restarts and unloads. */
    void setAssignment(ServiceKey service, boolean inferred);

    /**
     * Removes the stored service. With {@code manual} the train is also marked as not to be
     * assigned automatically until an operator assigns it again or clears that mark.
     */
    void clearAssignment(boolean manual);

    /** Sets the TrainCarts destination its path-finding routes the train to; empty clears it. */
    void setDestination(String destination);

    /** Removes the stored service and marks the train as sent to the depot, which survives unloads. */
    void markForDepot();

    /** Whether the train still has the name TrainCarts generated, rather than one a person chose. */
    boolean hasGeneratedName();

    /** Renames the train; false when the name is taken or not allowed. */
    boolean rename(String newName);

    boolean keepsChunksLoaded();

    /** The train's speed limit, in blocks per tick. */
    double speedLimit();

    void setSpeedLimit(double blocksPerTick);

    /** Switches TrainCarts' keep-chunks-loaded on or off for the train. */
    void keepChunksLoaded(boolean keep);
}
