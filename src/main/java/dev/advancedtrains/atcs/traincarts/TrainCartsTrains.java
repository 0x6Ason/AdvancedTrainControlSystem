package dev.advancedtrains.atcs.traincarts;

import com.bergerkiller.bukkit.tc.TrainCarts;
import com.bergerkiller.bukkit.tc.actions.Action;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartGroupStore;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.controller.components.SignTracker;
import com.bergerkiller.bukkit.tc.controller.spawnable.SpawnableGroup;
import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.offline.train.OfflineGroup;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import com.bergerkiller.bukkit.tc.signactions.SignAction;
import com.bergerkiller.bukkit.tc.signactions.SignActionType;
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

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

/** TrainCarts trains: the loaded ones, every stored one, and spawning new ones. */
public final class TrainCartsTrains implements TrainSource, TrainSpawner {
    /** The {@code atcs} sign action once registered, to find the signs trains stand at. */
    private @Nullable SignAction signAction;

    public void setSignAction(@Nullable SignAction action) {
        this.signAction = action;
    }

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
    public List<BlockPos> storedChunks(String name) {
        OfflineGroup stored = TrainCarts.plugin.getOfflineGroups().findGroup(name);
        if (stored == null || stored.isLoadedAsGroup()) {
            return List.of();
        }
        World world = stored.world.getLoadedWorld();
        if (world == null) {
            return List.of();
        }
        List<BlockPos> chunks = new ArrayList<>();
        stored.forAllChunks((cx, cz) -> chunks.add(new BlockPos(world.getName(), cx << 4, 64, cz << 4)));
        return chunks;
    }

    /**
     * Sends standing trains on their way again that have nothing to move them: the queue of
     * TrainCarts actions is empty, or its head is a hold left by an earlier run of ATCS (a plugin
     * reload keeps the old action in TrainCarts' queue). The {@code atcs} station or block sign the
     * train stands on is given the train again, which stops it afresh and launches it; when the
     * sign has nothing for it (ATCS now has it run through, or the sign is switched off), the train
     * is launched onwards instead, for a train left standing with an empty queue never moves again.
     * A stale hold on a train no longer seen on its sign is dropped, so the launch queued behind it
     * runs.
     */
    @Override
    public List<Relaunch> relaunchStranded(Predicate<TrainHandle> wanted, Predicate<String> standingLongEnough) {
        SignAction action = signAction;
        if (action == null) {
            return List.of();
        }
        List<Relaunch> relaunched = new ArrayList<>();
        for (MinecartGroup group : MinecartGroupStore.getGroups().cloneAsIterable()) {
            if (!usable(group) || group.isMoving() || !stranded(group)) {
                continue;
            }
            // A hold of an ATCS that is gone never ends by itself; an empty queue may be a train
            // between two actions, so that one must have stood a while.
            boolean staleHold = group.getActions().hasAction();
            List<SignTracker.ActiveSign> signs = stopSigns(group);
            if (signs.isEmpty() && !staleHold) {
                continue;
            }
            TrainCartsTrain train = new TrainCartsTrain(group);
            if (!wanted.test(train) || (!staleHold && !standingLongEnough.test(train.name()))) {
                continue;
            }
            if (signs.isEmpty()) {
                while (stranded(group) && group.getActions().hasAction()) {
                    group.getActions().removeAction();
                }
                boolean queued = group.getActions().hasAction();
                if (!queued) {
                    sendOn(group);
                }
                relaunched.add(new Relaunch(train.name(), queued ? Relaunch.How.HOLD_DROPPED : Relaunch.How.SENT_ON));
                continue;
            }
            group.getActions().clear();
            for (SignTracker.ActiveSign sign : signs) {
                if (!usable(group) || group.isMoving() || group.getActions().hasAction()) {
                    break;
                }
                SignActionEvent enter = sign.getSign().createEvent(SignActionType.GROUP_ENTER);
                enter.setGroup(group);
                SignAction.executeOne(action, enter);
            }
            if (!usable(group)) {
                continue;
            }
            boolean restopped = group.getActions().hasAction() || group.isMoving();
            if (!restopped) {
                sendOn(group);
            }
            relaunched.add(new Relaunch(train.name(), restopped ? Relaunch.How.STOPPED_AFRESH : Relaunch.How.SENT_ON));
        }
        return relaunched;
    }

    /**
     * The {@code atcs} station and block-entry signs the train is on, by the sign's text rather than
     * its action: a sign a train already stood on before a reload still carries the action of the
     * ATCS that is gone.
     */
    private static List<SignTracker.ActiveSign> stopSigns(MinecartGroup group) {
        List<SignTracker.ActiveSign> signs = new ArrayList<>();
        for (SignTracker.ActiveSign sign : group.getSignTracker().getActiveTrackedSigns().cloneAsIterable()) {
            SignActionEvent probe = sign.getSign().createEvent(SignActionType.NONE);
            if (probe.isType("atcs") && AtcsSignAction.stopsTrains(AtcsSignAction.parse(probe))) {
                signs.add(sign);
            }
        }
        return signs;
    }

    /** Sets a standing train going again the way it faces, at its speed limit, as a spawn does. */
    private static void sendOn(MinecartGroup group) {
        group.getActions().clear();
        group.setForwardForce(group.getProperties().getSpeedLimit());
    }

    /** No action moves the train, or the one at the head is a hold of an ATCS that is gone. */
    private static boolean stranded(MinecartGroup group) {
        Action current = group.getActions().getCurrentAction();
        if (current == null) {
            return true;
        }
        return staleHold(current);
    }

    /** A hold made by an earlier run of ATCS, whose classes were loaded by a loader that is gone. */
    static boolean staleHold(Action action) {
        return !(action instanceof HoldAction) && action.getClass().getName().equals(HoldAction.class.getName());
    }

    @Override
    public boolean destroy(TrainHandle train) {
        if (!(train instanceof TrainCartsTrain handle)) {
            return false;
        }
        return handle.destroy();
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
