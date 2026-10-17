package dev.advancedtrains.atcs.board;

import dev.advancedtrains.atcs.config.Messages;
import dev.advancedtrains.atcs.network.Board;
import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.operation.Departures;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.logging.Logger;

/**
 * Writes departures onto board signs. A sign is only touched when its text changes and its chunk is
 * loaded; signs are never loaded or created by ATCS.
 */
public final class BoardService {
    private final Logger logger;
    private final Map<BlockPos, List<Component>> written = new HashMap<>();
    private final Set<BlockPos> warned = new HashSet<>();

    public BoardService(Logger logger) {
        this.logger = logger;
    }

    /**
     * @param departures departures for a board, soonest first
     */
    public void update(Network network, Messages messages, BiFunction<Board, Integer, List<Departures.Departure>> departures) {
        for (Board board : network.boards().values()) {
            if (board.allSigns().isEmpty() || network.station(board.stationId()).isEmpty()) {
                continue;
            }
            List<Departures.Departure> upcoming = departures.apply(board, board.lines());
            Map<Integer, List<Component>> rendered = new HashMap<>();
            for (int index = 0; index < board.signs().size(); index++) {
                BlockPos position = board.signs().get(index);
                if (network.platformAt(position).isPresent()) {
                    continue;
                }
                write(position, line(rendered, upcoming, index, network, messages));
            }
            for (Map.Entry<BlockPos, Integer> mirror : board.mirrors().entrySet()) {
                if (network.platformAt(mirror.getKey()).isPresent()) {
                    continue;
                }
                write(mirror.getKey(), line(rendered, upcoming, mirror.getValue() - 1, network, messages));
            }
        }
    }

    /** The four lines for the n-th departure (0-based), rendered once per update. */
    private static List<Component> line(Map<Integer, List<Component>> rendered, List<Departures.Departure> upcoming, int index,
                                        Network network, Messages messages) {
        return rendered.computeIfAbsent(index, i -> i < upcoming.size()
                ? BoardRenderer.departure(upcoming.get(i), network, messages)
                : BoardRenderer.empty(messages));
    }

    /** Forgets what was written so every sign is rewritten on the next update. */
    public void invalidate() {
        written.clear();
        warned.clear();
    }

    private void write(BlockPos position, List<Component> lines) {
        if (lines.equals(written.get(position))) {
            return;
        }
        World world = Bukkit.getWorld(position.world());
        if (world == null || !world.isChunkLoaded(position.chunkX(), position.chunkZ())) {
            return;
        }
        Block block = world.getBlockAt(position.x(), position.y(), position.z());
        if (!(block.getState() instanceof Sign sign)) {
            if (warned.add(position)) {
                logger.warning("Departure board sign at " + position + " is missing; edit network.yml or /atcs board");
            }
            return;
        }
        // Both sides, so free-standing signs read the same from either direction.
        for (Side side : Side.values()) {
            SignSide signSide = sign.getSide(side);
            for (int index = 0; index < 4; index++) {
                signSide.line(index, lines.get(index));
            }
        }
        sign.setWaxed(true);
        sign.update(false, false);
        written.put(position, lines);
        warned.remove(position);
    }
}
