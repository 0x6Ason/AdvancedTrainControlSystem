package dev.advancedtrains.atcs.traincarts;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.controller.global.SignController;
import com.bergerkiller.bukkit.tc.controller.global.SignControllerWorld;
import com.bergerkiller.bukkit.tc.events.GroupCreateEvent;
import com.bergerkiller.bukkit.tc.events.GroupRemoveEvent;
import com.bergerkiller.bukkit.tc.events.GroupUnloadEvent;
import com.bergerkiller.bukkit.tc.events.MemberBlockChangeEvent;
import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.events.seat.MemberSeatEnterEvent;
import com.bergerkiller.bukkit.tc.events.seat.MemberSeatExitEvent;
import com.bergerkiller.bukkit.tc.signactions.SignActionType;
import dev.advancedtrains.atcs.importer.TrainSign;
import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.NetworkStore;
import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.runtime.RailwayRuntime;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Feeds TrainCarts events into the runtime. Platform triggers are existing TrainCarts signs (usually
 * {@code station} signs) bound in network.yml, observed without changing what the sign does.
 */
public final class TrainCartsListener implements Listener {
    private static final int MAX_MEMBERS_CHECKED = 64;

    private final Plugin plugin;
    private final NetworkStore store;
    private final RailwayRuntime runtime;
    /** Names of trains whose chunks unloaded; TrainCarts reports them as created when they reload. */
    private final Set<String> unloaded = new HashSet<>();

    public TrainCartsListener(Plugin plugin, NetworkStore store, RailwayRuntime runtime) {
        this.plugin = plugin;
        this.store = store;
        this.runtime = runtime;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignAction(SignActionEvent event) {
        SignActionType action = event.getAction();
        if ((action != SignActionType.GROUP_ENTER && action != SignActionType.GROUP_LEAVE) || !event.hasGroup()) {
            return;
        }
        if (!TrainCartsTrains.usable(event.getGroup())) {
            // A train being removed still fires leave events for the signs it covered.
            return;
        }
        Block sign = event.getBlock();
        if (sign == null) {
            return;
        }
        BlockPos position = new BlockPos(sign.getWorld().getName(), sign.getX(), sign.getY(), sign.getZ());
        Optional<PlatformRef> platform = store.network().platformAt(position);
        if (platform.isPresent()) {
            runtime.platformSignal(new TrainCartsTrain(event.getGroup()), platform.get(), position,
                    action == SignActionType.GROUP_ENTER);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGroupCreate(GroupCreateEvent event) {
        MinecartGroup group = event.getGroup();
        // Wait a tick so a spawn sign has placed every cart and applied its properties.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!TrainCartsTrains.usable(group)) {
                return;
            }
            TrainCartsTrain train = new TrainCartsTrain(group);
            if (unloaded.remove(train.name())) {
                runtime.trainReloaded(train);
            } else {
                runtime.trainCreated(train, spawnSignNear(group));
            }
        });
    }

    /** The TrainCarts spawn sign on the rails under the train, if it was just spawned by one. */
    static @Nullable BlockPos spawnSignNear(MinecartGroup group) {
        SignController controller = group.getTrainCarts().getSignController();
        SignControllerWorld world = controller.forWorld(group.getWorld());
        int checked = 0;
        for (MinecartMember<?> member : group) {
            if (++checked > MAX_MEMBERS_CHECKED) {
                break;
            }
            for (SignController.Entry entry : world.findNearby(member.getBlock(), false)) {
                if (isSpawnSign(entry.sign.getFrontLines()) || isSpawnSign(entry.sign.getBackLines())) {
                    Block sign = entry.getBlock();
                    return new BlockPos(sign.getWorld().getName(), sign.getX(), sign.getY(), sign.getZ());
                }
            }
        }
        return null;
    }

    private static boolean isSpawnSign(String[] lines) {
        return lines.length >= 2 && TrainSign.isTrainHeader(lines[0])
                && lines[1].strip().toLowerCase(Locale.ROOT).startsWith("spawn");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGroupRemove(GroupRemoveEvent event) {
        String name = event.getGroup().getProperties().getTrainName();
        unloaded.remove(name);
        runtime.trainRemoved(name);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGroupUnload(GroupUnloadEvent event) {
        String name = event.getGroup().getProperties().getTrainName();
        unloaded.add(name);
        runtime.trainUnloaded(name);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSeatEnter(MemberSeatEnterEvent event) {
        if (event.isPlayer() && !event.wasSeatChange() && event.getEntity() instanceof Player player) {
            MinecartGroup group = event.getGroup();
            if (group != null) {
                runtime.riderBoarded(new TrainCartsTrain(group), player);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSeatExit(MemberSeatExitEvent event) {
        if (event.isPlayer() && !event.isSeatChange() && event.getEntity() instanceof Player player) {
            runtime.riderLeft(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockChange(MemberBlockChangeEvent event) {
        MinecartGroup group = event.getGroup();
        if (group == null || group.isEmpty() || event.getMember() != group.head()) {
            return;
        }
        String name = group.getProperties().getTrainName();
        if (runtime.recordingPath(name)) {
            Block to = event.getTo();
            runtime.headMoved(name, to.getWorld().getName(), to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        runtime.riderLeft(event.getPlayer());
    }
}
