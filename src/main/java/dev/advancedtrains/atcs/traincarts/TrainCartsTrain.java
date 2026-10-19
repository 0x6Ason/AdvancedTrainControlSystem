package dev.advancedtrains.atcs.traincarts;

import com.bergerkiller.bukkit.tc.actions.Action;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import com.bergerkiller.bukkit.tc.properties.standard.type.ChunkLoadOptions;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.operation.ServiceResolver;
import dev.advancedtrains.atcs.operation.TrainInfo;
import dev.advancedtrains.atcs.runtime.TrainHandle;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** A TrainCarts train ({@link MinecartGroup}) seen through {@link TrainHandle}. */
final class TrainCartsTrain implements TrainHandle {
    private final MinecartGroup group;

    TrainCartsTrain(MinecartGroup group) {
        this.group = Objects.requireNonNull(group, "group");
    }

    @Override
    public String name() {
        return group.getProperties().getTrainName();
    }

    @Override
    public TrainInfo info() {
        TrainProperties properties = group.getProperties();
        String destination = properties.hasDestination() ? properties.getDestination() : "";
        return TrainInfo.of(properties.getTrainName(), properties.getTags(), destination,
                properties.getDestinationRoute());
    }

    @Override
    public @Nullable Location headLocation() {
        if (group.isEmpty()) {
            return null;
        }
        return group.head().getEntity().getLocation();
    }

    @Override
    public double speed() {
        return group.isEmpty() ? 0 : Math.abs(group.head().getRealSpeed());
    }

    @Override
    public List<Player> riders() {
        List<Player> riders = new ArrayList<>();
        for (MinecartMember<?> member : group) {
            riders.addAll(member.getEntity().getPlayerPassengers());
        }
        return riders;
    }

    @Override
    public void setAssignment(ServiceKey service, boolean inferred) {
        TrainProperties properties = group.getProperties();
        removeAtcsTags(properties);
        if (inferred) {
            properties.addTags(ServiceResolver.assignmentTag(service), ServiceResolver.TAG_INFERRED);
        } else {
            properties.addTags(ServiceResolver.assignmentTag(service));
        }
    }

    @Override
    public void clearAssignment(boolean manual) {
        TrainProperties properties = group.getProperties();
        removeAtcsTags(properties);
        if (manual) {
            properties.addTags(ServiceResolver.manualTag());
        }
    }

    @Override
    public void setDestination(String destination) {
        group.getProperties().setDestination(destination);
    }

    @Override
    public void markForDepot() {
        TrainProperties properties = group.getProperties();
        removeAtcsTags(properties);
        properties.addTags(ServiceResolver.depotTag());
    }

    /** Removes the train from the world. */
    boolean destroy() {
        if (group.isEmpty() || group.isUnloaded()) {
            return false;
        }
        group.destroy();
        return true;
    }

    @Override
    public double speedLimit() {
        return group.getProperties().getSpeedLimit();
    }

    @Override
    public void setSpeedLimit(double blocksPerTick) {
        group.getProperties().setSpeedLimit(blocksPerTick);
    }

    @Override
    public boolean hasGeneratedName() {
        return !group.getProperties().isTrainRenamed();
    }

    @Override
    public boolean rename(String newName) {
        if (TrainPropertiesStore.exists(newName) || !TrainPropertiesStore.isUseableName(newName)) {
            return false;
        }
        group.getProperties().setTrainName(newName);
        return true;
    }

    @Override
    public boolean keepsChunksLoaded() {
        return group.getProperties().getChunkLoadOptions().mode() != ChunkLoadOptions.Mode.DISABLED;
    }

    @Override
    public void keepChunksLoaded(boolean keep) {
        group.getProperties().parseSet("keepchunksloaded", Boolean.toString(keep));
    }

    @Override
    public String activity() {
        Collection<Action> queued = group.getActions().getScheduledActions();
        Action current = group.getActions().getCurrentAction();
        if (current == null) {
            return "nothing queued to move it";
        }
        String what;
        if (current instanceof HoldAction hold) {
            what = "ATCS holds it: " + hold.reason();
        } else if (TrainCartsTrains.staleHold(current)) {
            what = "a hold left by an earlier run of ATCS";
        } else {
            what = "TrainCarts " + current.getClass().getSimpleName() + " for " + current.elapsedTicks() / 20 + "s";
        }
        return queued.size() > 1 ? what + ", then " + (queued.size() - 1) + " more action(s)" : what;
    }

    private static void removeAtcsTags(TrainProperties properties) {
        List<String> stale = properties.getTags().stream()
                .filter(tag -> ServiceResolver.isAssignmentTag(tag) || ServiceResolver.isInferredTag(tag))
                .toList();
        if (!stale.isEmpty()) {
            properties.removeTags(stale.toArray(String[]::new));
        }
    }
}
