package dev.advancedtrains.atcs.command;

import dev.advancedtrains.atcs.importer.ImportService;
import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Ids;
import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.NetworkStore;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.network.Station;
import dev.advancedtrains.atcs.runtime.RailwayRuntime;
import dev.advancedtrains.atcs.runtime.TrainHandle;
import dev.advancedtrains.atcs.runtime.TrainSource;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** Lookups, edits and completions shared by all commands. */
public final class Support {
    public static final String USE = "atcs.use";
    public static final String MANAGE = "atcs.manage";

    final NetworkStore store;
    final RailwayRuntime runtime;
    final TrainSource trains;
    final ImportService importer;
    final PluginControl control;

    public Support(NetworkStore store, RailwayRuntime runtime, TrainSource trains, ImportService importer, PluginControl control) {
        this.store = store;
        this.runtime = runtime;
        this.trains = trains;
        this.importer = importer;
        this.control = control;
    }

    /** Plugin-level operations available to commands. */
    public interface PluginControl {
        /** Reloads configuration, messages and network.yml; returns problems to report. */
        List<String> reload();

        List<String> statusLines();

        void setDebug(boolean debug);

        boolean debug();

        /** Loaded worlds in which TrainCarts is enabled. */
        List<org.bukkit.World> railwayWorlds();
    }

    Network network() {
        return store.network();
    }

    Station station(String id) throws CommandError {
        return network().station(id.toLowerCase(java.util.Locale.ROOT))
                .orElseThrow(() -> new CommandError("Unknown station '" + id + "'. See /atcs station list"));
    }

    Line line(String id) throws CommandError {
        return network().line(id.toLowerCase(java.util.Locale.ROOT))
                .orElseThrow(() -> new CommandError("Unknown line '" + id + "'. See /atcs line list"));
    }

    ServiceKey serviceKey(String text) throws CommandError {
        try {
            return ServiceKey.parse(text);
        } catch (IllegalArgumentException exception) {
            throw new CommandError("Services are written line/service, for example a/local");
        }
    }

    Service service(String text) throws CommandError {
        ServiceKey key = serviceKey(text);
        return network().service(key)
                .orElseThrow(() -> new CommandError("Unknown service '" + key + "'. See /atcs line info " + key.lineId()));
    }

    static String id(String kind, String text) throws CommandError {
        try {
            return Ids.normalize(kind, text);
        } catch (IllegalArgumentException exception) {
            throw new CommandError(exception.getMessage());
        }
    }

    Network edit(UnaryOperator<Network> change) throws CommandError {
        try {
            return store.edit(change);
        } catch (NetworkStore.EditRejectedException exception) {
            throw new CommandError(exception.getMessage());
        } catch (IllegalArgumentException exception) {
            throw new CommandError(exception.getMessage());
        } catch (IOException exception) {
            throw new CommandError("Could not save network.yml: " + exception.getMessage());
        }
    }

    /**
     * The train named by argument {@code index}, or else the one the player rides, or else the one
     * the player looks at.
     */
    TrainHandle train(CommandContext context, int index) throws CommandError {
        if (index < context.size()) {
            String name = context.arg(index);
            return trains.train(name).orElseThrow(() -> new CommandError("No loaded train named '" + name + "'"));
        }
        if (context.sender() instanceof Player player) {
            Optional<TrainHandle> train = trains.trainOf(player).or(() -> trains.trainLookedAt(player));
            if (train.isPresent()) {
                return train.get();
            }
        }
        throw new CommandError("Ride or look at a train, or give its name");
    }

    /** The sign the player is looking at. */
    static Block targetSign(Player player) throws CommandError {
        Block block = player.getTargetBlockExact(6);
        if (block == null || !(block.getState() instanceof Sign)) {
            throw new CommandError("Look at a sign within 6 blocks");
        }
        return block;
    }

    static BlockPos position(Block block) {
        return new BlockPos(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    List<String> stationIds() {
        return List.copyOf(network().stations().keySet());
    }

    List<String> lineIds() {
        return List.copyOf(network().lines().keySet());
    }

    List<String> serviceKeys() {
        List<String> keys = new ArrayList<>();
        network().services().forEach(service -> keys.add(service.key().toString()));
        return keys;
    }

    List<String> platformIds(String stationId) {
        return network().station(stationId.toLowerCase(java.util.Locale.ROOT))
                .map(station -> List.copyOf(station.platforms().keySet()))
                .orElse(List.of());
    }

    List<String> boardIds() {
        return List.copyOf(network().boards().keySet());
    }

    List<String> trainNames() {
        return trains.loadedTrains().stream().map(TrainHandle::name).toList();
    }
}
