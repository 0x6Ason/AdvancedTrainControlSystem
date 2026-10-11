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

    List<Player> riders();

    /** Stores the service assignment on the train itself (a TrainCarts tag), or clears it. */
    void setAssignment(@Nullable ServiceKey service);
}
