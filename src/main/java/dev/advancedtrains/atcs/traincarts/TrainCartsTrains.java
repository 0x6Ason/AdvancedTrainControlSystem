package dev.advancedtrains.atcs.traincarts;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartGroupStore;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import dev.advancedtrains.atcs.runtime.TrainHandle;
import dev.advancedtrains.atcs.runtime.TrainSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Loaded TrainCarts trains. */
public final class TrainCartsTrains implements TrainSource {

    @Override
    public Collection<TrainHandle> loadedTrains() {
        List<TrainHandle> result = new ArrayList<>();
        for (MinecartGroup group : MinecartGroupStore.getGroups().cloneAsIterable()) {
            if (usable(group)) {
                result.add(new TrainCartsTrain(group));
            }
        }
        return result;
    }

    @Override
    public Optional<TrainHandle> train(String name) {
        for (MinecartGroup group : MinecartGroupStore.getGroups().cloneAsIterable()) {
            if (usable(group) && group.getProperties().getTrainName().equalsIgnoreCase(name)) {
                return Optional.of(new TrainCartsTrain(group));
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<TrainHandle> trainOf(Player player) {
        Entity vehicle = player.getVehicle();
        if (vehicle != null) {
            MinecartGroup group = MinecartGroupStore.get(vehicle);
            if (group != null && usable(group)) {
                return Optional.of(new TrainCartsTrain(group));
            }
        }
        for (MinecartGroup group : MinecartGroupStore.getGroups().cloneAsIterable()) {
            if (!usable(group)) {
                continue;
            }
            for (MinecartMember<?> member : group) {
                if (member.getEntity().getPlayerPassengers().contains(player)) {
                    return Optional.of(new TrainCartsTrain(group));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<TrainHandle> trainLookedAt(Player player) {
        Entity target = player.getTargetEntity(6);
        if (target == null) {
            return Optional.empty();
        }
        MinecartGroup group = MinecartGroupStore.get(target);
        return group != null && usable(group) ? Optional.of(new TrainCartsTrain(group)) : Optional.empty();
    }

    static boolean usable(MinecartGroup group) {
        return !group.isEmpty() && !group.isUnloaded() && !group.isRemoved();
    }
}
