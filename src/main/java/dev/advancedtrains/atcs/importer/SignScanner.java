package dev.advancedtrains.atcs.importer;

import dev.advancedtrains.atcs.network.BlockPos;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * Reads the text of every sign in chunks that are already loaded, a few chunks per tick. It never
 * loads or generates chunks, so it cannot wake up TrainCarts spawners or change the world.
 */
public final class SignScanner {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    public record Result(List<ScannedSign> signs, int chunksScanned, int chunksSkipped) {
    }

    private record ChunkRef(World world, int x, int z) {
    }

    private BukkitTask task;

    public boolean running() {
        return task != null && !task.isCancelled();
    }

    public void start(Plugin plugin, Collection<World> worlds, int chunksPerTick, Consumer<Result> done) {
        if (running()) {
            throw new IllegalStateException("A scan is already running");
        }
        Deque<ChunkRef> queue = new ArrayDeque<>();
        for (World world : worlds) {
            for (Chunk chunk : world.getLoadedChunks()) {
                queue.add(new ChunkRef(world, chunk.getX(), chunk.getZ()));
            }
        }
        List<ScannedSign> signs = new ArrayList<>();
        int[] counts = new int[2];
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (int processed = 0; processed < chunksPerTick && !queue.isEmpty(); processed++) {
                ChunkRef ref = queue.poll();
                if (!ref.world().isChunkLoaded(ref.x(), ref.z())) {
                    counts[1]++;
                    continue;
                }
                counts[0]++;
                for (BlockState state : ref.world().getChunkAt(ref.x(), ref.z()).getTileEntities()) {
                    if (state instanceof Sign sign) {
                        read(ref.world(), sign, signs);
                    }
                }
            }
            if (queue.isEmpty()) {
                cancel();
                done.accept(new Result(signs, counts[0], counts[1]));
            }
        }, 1L, 1L);
    }

    public void cancel() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private static void read(World world, Sign sign, List<ScannedSign> out) {
        BlockPos position = new BlockPos(world.getName(), sign.getX(), sign.getY(), sign.getZ());
        for (Side side : Side.values()) {
            List<String> lines = new ArrayList<>(4);
            for (Component line : sign.getSide(side).lines()) {
                lines.add(PLAIN.serialize(line));
            }
            ScannedSign scanned = new ScannedSign(position, side == Side.FRONT, lines);
            if (!scanned.blank()) {
                out.add(scanned);
            }
        }
    }
}
