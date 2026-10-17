package dev.advancedtrains.atcs.runtime;

import dev.advancedtrains.atcs.network.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Keeps a small set of chunks loaded with plugin chunk tickets: the chunks of spawn signs and fleet
 * spawn points, so timed spawn signs keep firing and fleets can spawn with nobody around. Nothing
 * else of the railway needs to stay loaded: running trains keep their own chunks loaded.
 */
public final class ChunkKeeper {
    private record ChunkRef(String world, int x, int z) {
    }

    private final Plugin plugin;
    private final Set<ChunkRef> held = new HashSet<>();

    public ChunkKeeper(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Holds exactly the chunks of these positions (in loaded worlds) and releases the others. */
    public void keep(Collection<BlockPos> positions) {
        Set<ChunkRef> wanted = new HashSet<>();
        for (BlockPos position : positions) {
            wanted.add(new ChunkRef(position.world(), position.chunkX(), position.chunkZ()));
        }
        for (ChunkRef chunk : Set.copyOf(held)) {
            if (!wanted.contains(chunk)) {
                World world = Bukkit.getWorld(chunk.world());
                if (world != null) {
                    world.removePluginChunkTicket(chunk.x(), chunk.z(), plugin);
                }
                held.remove(chunk);
            }
        }
        for (ChunkRef chunk : wanted) {
            if (held.contains(chunk)) {
                continue;
            }
            World world = Bukkit.getWorld(chunk.world());
            if (world != null && world.addPluginChunkTicket(chunk.x(), chunk.z(), plugin)) {
                held.add(chunk);
            }
        }
    }

    public int heldChunks() {
        return held.size();
    }

    public void release() {
        if (held.isEmpty()) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            world.removePluginChunkTickets(plugin);
        }
        held.clear();
    }
}
