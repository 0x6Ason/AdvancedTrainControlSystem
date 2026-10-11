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
            if (board.signs().isEmpty() || network.station(board.stationId()).isEmpty()) {
                continue;
            }
            List<Departures.Departure> upcoming = departures.apply(board, board.signs().size());
            for (int index = 0; index < board.signs().size(); index++) {
                BlockPos position = board.signs().get(index);
                if (network.platformAt(position).isPresent()) {
                    continue;
                }
                List<Component> lines = index < upcoming.size()
                        ? BoardRenderer.departure(upcoming.get(index), network, messages)
                        : BoardRenderer.empty(messages);
                write(position, lines);
            }
        }
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
