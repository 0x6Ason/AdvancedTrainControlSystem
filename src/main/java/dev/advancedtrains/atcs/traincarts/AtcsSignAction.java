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
import dev.advancedtrains.atcs.runtime.RailwayRuntime;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;

/** The {@code atcs stop}, {@code atcs service} and {@code atcs clear} TrainCarts signs. */
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
        AtcsSign sign;
        try {
            sign = AtcsSign.parse(info.getLine(1), info.getLine(2), info.getLine(3));
        } catch (IllegalArgumentException invalid) {
            return;
        }
        TrainCartsTrain train = new TrainCartsTrain(info.getGroup());
        switch (sign) {
            case AtcsSign.StopSign stop -> {
                Block block = info.getBlock();
                if (block != null) {
                    BlockPos position = new BlockPos(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
                    runtime.platformSignal(train, new PlatformRef(stop.stationId(), stop.platformId()), position, entering);
                }
            }
            case AtcsSign.ServiceSign service when entering && info.isPowered() -> runtime.assign(train, service.service());
            case AtcsSign.ClearSign ignored when entering && info.isPowered() -> runtime.assign(train, null);
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
        }
        return options.handle(event);
    }

    @Override
    public boolean canSupportRC() {
        return false;
    }

    private static void warn(SignChangeActionEvent event, String message) {
        event.getPlayer().sendMessage(Component.text("[ATCS] " + message, NamedTextColor.GOLD));
    }
}
