package dev.advancedtrains.atcs.traincarts;

import com.bergerkiller.bukkit.tc.TrainCarts;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartGroupStore;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.controller.spawnable.SpawnableGroup;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Fleet;
import dev.advancedtrains.atcs.operation.TrainInfo;
import dev.advancedtrains.atcs.runtime.TrainHandle;
import dev.advancedtrains.atcs.runtime.TrainSource;
import dev.advancedtrains.atcs.runtime.TrainSpawner;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Rail;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** TrainCarts trains: the loaded ones, every stored one, and spawning new ones. */
public final class TrainCartsTrains implements TrainSource, TrainSpawner {

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

    @Override
    public Collection<TrainInfo> allTrains() {
        List<TrainInfo> result = new ArrayList<>();
        for (TrainProperties properties : TrainPropertiesStore.getAll()) {
            String destination = properties.hasDestination() ? properties.getDestination() : "";
            result.add(TrainInfo.of(properties.getTrainName(), properties.getTags(), destination,
                    properties.getDestinationRoute()));
        }
        return result;
    }

    @Override
    public boolean nameTaken(String name) {
        return TrainPropertiesStore.exists(name);
    }

    @Override
    public Result spawn(BlockPos at, Fleet.Direction direction, String pattern) {
        World world = Bukkit.getWorld(at.world());
        if (world == null) {
            return Result.failed("world '" + at.world() + "' is not loaded");
        }
        world.getChunkAt(at.chunkX(), at.chunkZ());
        Block block = world.getBlockAt(at.x(), at.y(), at.z());
        if (!(block.getBlockData() instanceof Rail)) {
            return Result.failed("there is no rail at " + at.coordinates());
        }
        SpawnableGroup group = SpawnableGroup.parse(TrainCarts.plugin, pattern);
        if (group.getMembers().isEmpty()) {
            return Result.failed("'" + pattern + "' is not a valid train pattern (try 4m)");
        }
        SpawnableGroup.SpawnLocationList locations = group.findSpawnLocations(block,
                new Vector(direction.dx(), 0, direction.dz()), SpawnableGroup.SpawnMode.DEFAULT);
        if (locations == null || locations.locations.size() < group.getMembers().size()) {
            return Result.failed("not enough track " + direction.name().toLowerCase(Locale.ROOT)
                    + " of " + at.coordinates() + " for that train");
        }
        if (!locations.getOccupiedLocations().isEmpty()) {
            return Result.failed("the track at " + at.coordinates() + " is occupied");
        }
        MinecartGroup spawned = group.spawn(locations);
        if (spawned == null || spawned.isEmpty()) {
            return Result.failed("TrainCarts did not spawn the train");
        }
        spawned.setForwardForce(spawned.getProperties().getSpeedLimit());
        return new Result(new TrainCartsTrain(spawned), "");
    }

    static boolean usable(MinecartGroup group) {
        return !group.isEmpty() && !group.isUnloaded() && !group.isRemoved();
    }
}
