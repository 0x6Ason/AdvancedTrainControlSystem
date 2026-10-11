package dev.advancedtrains.atcs.traincarts;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.operation.ServiceResolver;
import dev.advancedtrains.atcs.operation.TrainInfo;
import dev.advancedtrains.atcs.runtime.TrainHandle;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
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

    private static void removeAtcsTags(TrainProperties properties) {
        List<String> stale = properties.getTags().stream()
                .filter(tag -> ServiceResolver.isAssignmentTag(tag) || ServiceResolver.isInferredTag(tag))
                .toList();
        if (!stale.isEmpty()) {
            properties.removeTags(stale.toArray(String[]::new));
        }
    }
}
