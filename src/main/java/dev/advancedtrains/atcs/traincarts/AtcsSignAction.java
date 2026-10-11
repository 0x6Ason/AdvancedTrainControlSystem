package dev.advancedtrains.atcs.traincarts;

import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.events.SignChangeActionEvent;
import com.bergerkiller.bukkit.tc.signactions.SignAction;
import com.bergerkiller.bukkit.tc.signactions.SignActionType;
import com.bergerkiller.bukkit.tc.utils.SignBuildOptions;
import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.NetworkStore;
import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.operation.AtcsSign;
import dev.advancedtrains.atcs.operation.Indicator;
import dev.advancedtrains.atcs.runtime.RailwayRuntime;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * The {@code atcs stop}, {@code atcs service}, {@code atcs clear} and {@code atcs indicator}
 * TrainCarts signs. Indicator signs are tracked while their chunks are loaded, through the
 * load, text-change and destroy callbacks TrainCarts gives every sign action.
 */
public final class AtcsSignAction extends SignAction {
    public static final String BUILD_PERMISSION = "atcs.sign.build";

    private final NetworkStore store;
    private final RailwayRuntime runtime;

    public AtcsSignAction(NetworkStore store, RailwayRuntime runtime) {
        this.store = store;
        this.runtime = runtime;
    }

    @Override
    public boolean match(SignActionEvent info) {
        return info.isType("atcs");
    }

    @Override
    public void execute(SignActionEvent info) {
        boolean entering = info.isAction(SignActionType.GROUP_ENTER);
        if ((!entering && !info.isAction(SignActionType.GROUP_LEAVE)) || !info.hasGroup()
                || !TrainCartsTrains.usable(info.getGroup())) {
            return;
        }
        AtcsSign sign = parse(info);
        if (sign == null) {
            return;
        }
        TrainCartsTrain train = new TrainCartsTrain(info.getGroup());
        switch (sign) {
            case AtcsSign.StopSign stop -> {
                Block block = info.getBlock();
                if (block != null) {
                    runtime.platformSignal(train, new PlatformRef(stop.stationId(), stop.platformId()), position(block), entering);
                }
            }
            case AtcsSign.ServiceSign service when entering && info.isPowered() -> runtime.assign(train, service.service());
            case AtcsSign.ClearSign ignored when entering && info.isPowered() -> runtime.unassign(train, true);
            default -> {
            }
        }
    }

    @Override
    public boolean build(SignChangeActionEvent event) {
        AtcsSign sign;
        try {
            sign = AtcsSign.parse(event.getLine(1), event.getLine(2), event.getLine(3));
        } catch (IllegalArgumentException invalid) {
            event.getPlayer().sendMessage(Component.text("[ATCS] " + invalid.getMessage(), NamedTextColor.RED));
            return false;
        }
        Network network = store.network();
        SignBuildOptions options = SignBuildOptions.create().setPermission(BUILD_PERMISSION);
        switch (sign) {
            case AtcsSign.StopSign stop -> {
                options.setName("ATCS platform").setDescription("mark platform " + stop.platformId()
                        + " of " + network.stationName(stop.stationId()) + " for passenger information");
                if (network.station(stop.stationId()).isEmpty()) {
                    warn(event, "Station '" + stop.stationId() + "' does not exist yet; create it with /atcs station create");
                }
            }
            case AtcsSign.ServiceSign service -> {
                options.setName("ATCS service").setDescription("assign trains to service " + service.service());
                if (network.service(service.service()).isEmpty()) {
                    warn(event, "Service '" + service.service() + "' does not exist yet; create it with /atcs service create");
                }
            }
            case AtcsSign.ClearSign ignored ->
                    options.setName("ATCS clear").setDescription("remove the ATCS service assignment of trains");
            case AtcsSign.IndicatorSign indicator -> {
                Indicator rule = indicator.indicator();
                options.setName("ATCS indicator").setDescription(rule.describe(network)
                        + ". Attach a lever to the block behind the sign");
                if (network.station(rule.stationId()).isEmpty()) {
                    warn(event, "Station '" + rule.stationId() + "' does not exist yet; create it with /atcs station create");
                } else if (rule.platformId() != null
                        && network.station(rule.stationId()).orElseThrow().platform(rule.platformId()).isEmpty()) {
                    warn(event, network.stationName(rule.stationId()) + " has no platform '" + rule.platformId() + "' yet");
                }
                if (rule.otherStationId() != null && network.station(rule.otherStationId()).isEmpty()) {
                    warn(event, "Station '" + rule.otherStationId() + "' does not exist yet; create it with /atcs station create");
                }
                if (rule.service() != null && network.service(rule.service()).isEmpty()) {
                    warn(event, "Service '" + rule.service() + "' does not exist yet; create it with /atcs service create");
                } else if (rule.lineId() != null && network.line(rule.lineId()).isEmpty()) {
                    warn(event, "Line '" + rule.lineId() + "' does not exist yet; create it with /atcs line create");
                }
            }
        }
        boolean built = options.handle(event);
        if (built && sign instanceof AtcsSign.IndicatorSign indicator && event.getBlock() != null) {
            runtime.indicators().register(position(event.getBlock()), indicator.indicator());
        }
        return built;
    }

    /** TrainCarts reports every matching sign when its chunk loads or unloads. */
    @Override
    public void loadedChanged(SignActionEvent info, boolean loaded) {
        Block block = info.getBlock();
        if (block == null) {
            return;
        }
        if (loaded && parse(info) instanceof AtcsSign.IndicatorSign indicator) {
            runtime.indicators().register(position(block), indicator.indicator());
        } else {
            runtime.indicators().unregister(position(block));
        }
    }

    @Override
    public boolean signTextChanged(SignActionEvent info) {
        loadedChanged(info, true);
        return true;
    }

    @Override
    public void destroy(SignActionEvent info) {
        Block block = info.getBlock();
        if (block != null) {
            runtime.indicators().unregister(position(block));
        }
    }

    @Override
    public boolean canSupportRC() {
        return false;
    }

    private static @Nullable AtcsSign parse(SignActionEvent info) {
        try {
            return AtcsSign.parse(info.getLine(1), info.getLine(2), info.getLine(3));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static BlockPos position(Block block) {
        return new BlockPos(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    private static void warn(SignChangeActionEvent event, String message) {
        event.getPlayer().sendMessage(Component.text("[ATCS] " + message, NamedTextColor.GOLD));
    }
}
