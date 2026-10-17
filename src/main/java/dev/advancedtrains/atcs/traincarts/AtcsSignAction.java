package dev.advancedtrains.atcs.traincarts;

import com.bergerkiller.bukkit.tc.Direction;
import com.bergerkiller.bukkit.tc.Station;
import com.bergerkiller.bukkit.tc.actions.GroupActionWaitStationRouting;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.components.ActionTrackerGroup;
import com.bergerkiller.bukkit.tc.controller.components.RailJunction;
import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.events.SignChangeActionEvent;
import com.bergerkiller.bukkit.tc.rails.type.RailType;
import com.bergerkiller.bukkit.tc.signactions.SignAction;
import com.bergerkiller.bukkit.tc.signactions.SignActionType;
import com.bergerkiller.bukkit.tc.utils.LauncherConfig;
import com.bergerkiller.bukkit.tc.utils.SignBuildOptions;
import dev.advancedtrains.atcs.control.Heading;
import dev.advancedtrains.atcs.control.StationControl;
import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.NetworkStore;
import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.operation.AtcsSign;
import dev.advancedtrains.atcs.operation.Indicator;
import dev.advancedtrains.atcs.operation.JourneyEvent;
import dev.advancedtrains.atcs.runtime.RailwayRuntime;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * The {@code atcs} TrainCarts signs: {@code stop}, {@code station}, {@code switch}, {@code service},
 * {@code clear} and {@code indicator}. Indicator signs are tracked while their chunks are loaded,
 * through the load, text-change and destroy callbacks TrainCarts gives every sign action.
 *
 * <p>A station sign stops a train the way TrainCarts' own station sign does, through TrainCarts'
 * {@link Station} helper and the train's action queue, but only for trains whose service calls
 * there; and it may keep the train standing after its dwell time through a {@link HoldAction}.
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
        // TrainCarts keeps a sign's action until the sign is seen again, so an action from before
        // a plugin reload can still be given trains: it does nothing once its ATCS has stopped.
        if (!runtime.running() || !info.hasGroup() || !TrainCartsTrains.usable(info.getGroup())) {
            return;
        }
        if (info.isAction(SignActionType.GROUP_RELOAD)) {
            reloaded(info);
            return;
        }
        boolean entering = info.isAction(SignActionType.GROUP_ENTER);
        if (!entering && !info.isAction(SignActionType.GROUP_LEAVE)) {
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
            case AtcsSign.StationSign station -> station(info, station, train, entering);
            case AtcsSign.SwitchSign switcher -> switchRails(info, switcher, train, entering);
            case AtcsSign.ServiceSign service when entering && info.isPowered() -> runtime.assign(train, service.service());
            case AtcsSign.ClearSign ignored when entering && info.isPowered() -> runtime.unassign(train, true);
            case AtcsSign.BlockSign block -> block(info, block, train, entering);
            case AtcsSign.SpeedSign speed when entering && info.isPowered() -> runtime.applySpeedSign(train, speed.speed(), speed.selector());
            default -> {
            }
        }
    }

    /**
     * A train loaded again on top of this sign (TrainCarts fires no enter for signs a train was
     * already on). One that stands still at a station or block sign on a service it is running
     * is stopped afresh, the way it was when it arrived: the ATCS hold it had was not saved with
     * the train, so the TrainCarts actions that came back would let it go without one, and a
     * train that lost its actions altogether would never go at all.
     */
    private void reloaded(SignActionEvent info) {
        MinecartGroup group = info.getGroup();
        if (group.isMoving() || !stopsTrains(parse(info))) {
            return;
        }
        TrainCartsTrain train = new TrainCartsTrain(group);
        if (!runtime.hasService(train)) {
            return;
        }
        group.getActions().clear();
        runtime.relaunched(train.name(), "was loaded again standing on its sign");
        execute(info.setAction(SignActionType.GROUP_ENTER));
    }

    /** Whether the sign is one that makes trains stand: a station, or the entry to a block. */
    static boolean stopsTrains(@Nullable AtcsSign sign) {
        return sign instanceof AtcsSign.StationSign || (sign instanceof AtcsSign.BlockSign block && block.entry());
    }

    /**
     * A station sign: reports the platform like a stop sign, then stops the train if its service
     * calls here. The stop itself is TrainCarts' station behaviour (centre, dwell, levers, launch),
     * with an ATCS hold between dwell and launch when a headway or a clear section is required.
     */
    private void station(SignActionEvent info, AtcsSign.StationSign sign, TrainCartsTrain train, boolean entering) {
        Block block = info.getBlock();
        if (block == null) {
            return;
        }
        PlatformRef platform = new PlatformRef(sign.stationId(), sign.platformId());
        List<JourneyEvent> events = runtime.platformSignal(train, platform, position(block), entering);
        MinecartGroup group = info.getGroup();
        if (!entering) {
            if (group.getActions().isWaitAction()) {
                group.getActions().clear();
            }
            info.setLevers(false);
            return;
        }
        if (!info.isPowered() || !info.hasRails() || !info.hasMember()) {
            return;
        }
        StationControl.Plan plan = runtime.planStop(train, platform, events, sign.options());
        if (plan == null) {
            return;
        }
        Station.StationConfig config = new Station.StationConfig();
        config.setInstruction(BlockFace.SELF);
        config.setDelay(Math.round(plan.dwellSeconds() * 1000));
        config.setNextDirection(Direction.parse(plan.heading().word()));
        config.setAutoRouting(plan.autoRoute());
        config.setOffsetFromCenter(plan.offset());
        if (plan.launchDistance() > 0) {
            LauncherConfig launch = LauncherConfig.createDefault();
            launch.setDistance(plan.launchDistance());
            config.setLaunchConfig(launch);
        }
        if (plan.launchSpeed() > 0) {
            config.setLaunchSpeed(plan.launchSpeed());
        }
        Station station = new Station(info, config);
        ActionTrackerGroup actions = group.getActions();
        actions.launchReset();
        boolean stopHere = station.hasDelay() || plan.mayHold() || runtime.heldByHand(train) || runtime.junctionPriorityOn();
        if (station.isAutoRouting()) {
            if (stopHere) {
                station.centerTrain();
                station.waitTrainKeepLeversDown(station.hasDelay() ? station.getDelay() : 0);
                hold(actions, train, platform, plan, station.getTag());
            }
            actions.addAction(new GroupActionWaitStationRouting(station, info.getRailPiece(), stopHere)).addTag(station.getTag());
            return;
        }
        BlockFace face = station.getNextDirectionFace();
        if (stopHere) {
            station.centerTrain();
            station.waitTrainKeepLeversDown(station.hasDelay() ? station.getDelay() : 0);
            hold(actions, train, platform, plan, station.getTag());
        } else if (!info.getMember().isDirectionTo(face)) {
            station.centerTrain();
        }
        station.launchTo(face);
    }

    private void hold(ActionTrackerGroup actions, TrainCartsTrain train, PlatformRef platform,
                      StationControl.Plan plan, String tag) {
        if (!plan.mayHold() && !runtime.heldByHand(train) && !runtime.junctionPriorityOn()) {
            return;
        }
        HoldAction hold = new HoldAction(() -> runtime.holdReason(train, platform, plan),
                (int) Math.round(plan.holdLimitSeconds() * 20), reason -> runtime.heldTooLong(train, platform, reason),
                () -> !runtime.heldByHand(train), runtime::running, error -> runtime.holdFailed(train, platform.toString(), error));
        actions.addAction(hold).addTag(tag);
    }

    /**
     * A block sign: at the entry, a train takes the block or waits on the sign until the train
     * inside has passed the exit; at the exit, it frees the block.
     */
    private void block(SignActionEvent info, AtcsSign.BlockSign sign, TrainCartsTrain train, boolean entering) {
        if (!sign.entry()) {
            if (entering) {
                runtime.blockExit(train, sign.blockId());
            }
            return;
        }
        if (!entering) {
            info.setLevers(false);
            return;
        }
        if (!info.isPowered() || !info.hasRails() || !info.hasMember()) {
            return;
        }
        Optional<String> inside = runtime.blockEnter(train, sign.blockId());
        info.setLevers(inside.isPresent());
        if (inside.isEmpty()) {
            return;
        }
        Station.StationConfig config = new Station.StationConfig();
        config.setInstruction(BlockFace.SELF);
        config.setDelay(0);
        config.setNextDirection(Direction.CONTINUE);
        Station station = new Station(info, config);
        MinecartGroup group = info.getGroup();
        ActionTrackerGroup actions = group.getActions();
        actions.launchReset();
        station.centerTrain();
        station.waitTrainKeepLeversDown(0);
        HoldAction hold = new HoldAction(() -> runtime.blockHoldReason(train, sign.blockId()),
                (int) Math.round(runtime.blockHoldLimitSeconds() * 20), reason -> runtime.blockHeldTooLong(train, sign.blockId(), reason),
                () -> true, runtime::running, error -> runtime.holdFailed(train, "block " + sign.blockId(), error));
        actions.addAction(hold).addTag(station.getTag());
        station.launchTo(station.getNextDirectionFace());
    }

    /** A switch sign: sets the junction for the direction the train's service needs. */
    private void switchRails(SignActionEvent info, AtcsSign.SwitchSign sign, TrainCartsTrain train, boolean entering) {
        if (!entering) {
            info.setLevers(false);
            return;
        }
        if (!info.isPowered() || !info.hasRailedMember()) {
            return;
        }
        Block signBlock = info.getBlock();
        Optional<Heading> heading = runtime.switchHeading(train, sign.rules(), signBlock == null ? null : position(signBlock));
        info.setLevers(heading.isPresent());
        if (heading.isPresent()) {
            // As on TrainCarts' station sign: continue and reverse follow the train, left and right
            // are as seen by a train driving into the front of the sign, the rest are absolute.
            BlockFace face = Direction.parse(heading.get().word())
                    .getDirectionLegacy(info.getFacing(), info.getCartEnterFace());
            // A vanilla rail offers a junction towards every side, so make sure track is there.
            RailJunction junction = trackTowards(info, face) ? info.findJunction(face) : null;
            if (junction != null) {
                info.setRailsTo(junction);
            } else {
                runtime.noJunction(train, heading.get());
            }
        }
    }

    /** Whether rails continue from the sign's rail block in that direction, level or sloped. */
    private static boolean trackTowards(SignActionEvent info, BlockFace face) {
        Block rails = info.getRails();
        if (rails == null) {
            return false;
        }
        Block next = rails.getRelative(face);
        return isTrack(next) || isTrack(next.getRelative(BlockFace.UP)) || isTrack(next.getRelative(BlockFace.DOWN));
    }

    private static boolean isTrack(Block block) {
        return RailType.getType(block) != RailType.NONE;
    }

    @Override
    public boolean build(SignChangeActionEvent event) {
        AtcsSign sign;
        try {
            sign = parseOrThrow(event);
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
            case AtcsSign.StationSign station -> {
                options.setName("ATCS station").setDescription(station.options().describe().replaceFirst("here",
                        "at platform " + station.platformId() + " of " + network.stationName(station.stationId())));
                if (network.station(station.stationId()).isEmpty()) {
                    warn(event, "Station '" + station.stationId() + "' does not exist yet; create it with /atcs station create");
                }
            }
            case AtcsSign.SwitchSign switcher -> {
                options.setName("ATCS switch").setDescription(switcher.rules().describe());
                List<String> unknown = switcher.rules().unknownSelectors(network);
                if (!unknown.isEmpty()) {
                    warn(event, "No line, service or station called " + String.join(", ", unknown) + " yet");
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
            case AtcsSign.BlockSign block -> options.setName("ATCS block").setDescription(block.entry()
                    ? "hold trains here while another train is inside block " + block.blockId() + " (until it passes the exit sign)"
                    : "free block " + block.blockId() + " when a train passes");
            case AtcsSign.SpeedSign speed -> options.setName("ATCS speed").setDescription("set the speed limit of "
                    + (speed.selector() == null ? "every passing train" : "passing " + speed.selector() + " trains")
                    + " to " + speed.speed() + " blocks per tick");
            case AtcsSign.IndicatorSign indicator -> {
                Indicator rule = indicator.indicator();
                options.setName("ATCS indicator").setDescription(rule.describe(network)
                        + ". Attach a lever to the block behind the sign");
                if (rule.mode() == Indicator.Mode.BLOCK) {
                    // Nothing to check: the block exists once its signs do.
                } else if (network.station(rule.stationId()).isEmpty()) {
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
        AtcsSign sign = loaded ? parse(info) : null;
        if (sign instanceof AtcsSign.IndicatorSign indicator) {
            runtime.indicators().register(position(block), indicator.indicator());
        } else {
            runtime.indicators().unregister(position(block));
        }
        runtime.switchSignLoaded(position(block), sign instanceof AtcsSign.SwitchSign switcher ? switcher.rules() : null);
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
            runtime.switchSignLoaded(position(block), null);
        }
    }

    @Override
    public boolean canSupportRC() {
        return false;
    }

    /** Station and switch signs act on trains from either direction, like TrainCarts' station sign. */
    @Override
    public boolean overrideFacing() {
        return true;
    }

    /** Switch signs, and station signs that let TrainCarts route, change the junction they sit on. */
    @Override
    public boolean isRailSwitcher(SignActionEvent info) {
        AtcsSign sign = parse(info);
        return sign instanceof AtcsSign.SwitchSign
                || (sign instanceof AtcsSign.StationSign station && station.options().autoRoute());
    }

    /**
     * With {@code control.set-destinations} on, every station sign is a TrainCarts destination
     * named {@code station:platform}, so TrainCarts path-finding can route trains to it.
     */
    @Override
    public @Nullable String getRailDestinationName(SignActionEvent info) {
        if (!runtime.setsDestinations()) {
            return null;
        }
        return parse(info) instanceof AtcsSign.StationSign station ? station.destinationName() : null;
    }

    static @Nullable AtcsSign parse(SignActionEvent info) {
        try {
            return parseOrThrow(info);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static AtcsSign parseOrThrow(SignActionEvent info) {
        String[] below = info.getExtraLinesBelow();
        return AtcsSign.parse(info.getLine(1), info.getLine(2), info.getLine(3), below == null ? List.of() : List.of(below));
    }

    private static BlockPos position(Block block) {
        return new BlockPos(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    private static void warn(SignChangeActionEvent event, String message) {
        event.getPlayer().sendMessage(Component.text("[ATCS] " + message, NamedTextColor.GOLD));
    }
}
