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
    public void setAssignment(@Nullable ServiceKey service) {
        TrainProperties properties = group.getProperties();
        List<String> stale = properties.getTags().stream().filter(ServiceResolver::isAssignmentTag).toList();
        if (!stale.isEmpty()) {
            properties.removeTags(stale.toArray(String[]::new));
        }
        if (service != null) {
            properties.addTags(ServiceResolver.assignmentTag(service));
        }
    }
}
