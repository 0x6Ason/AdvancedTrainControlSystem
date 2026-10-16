package dev.advancedtrains.atcs.selection;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import dev.advancedtrains.atcs.network.Box;
import org.bukkit.entity.Player;

import java.util.Optional;

/**
 * Reads a player's WorldEdit selection. Only loaded when WorldEdit is installed, so its classes
 * are never resolved otherwise.
 */
final class WorldEditSelections {

    WorldEditSelections() {
        // Resolve the API eagerly so an incompatible WorldEdit fails here, at start-up, not on first use.
        WorldEdit.getInstance().getSessionManager();
    }

    Optional<Box> selection(Player player) {
        LocalSession session = WorldEdit.getInstance().getSessionManager().getIfPresent(BukkitAdapter.adapt(player));
        if (session == null) {
            return Optional.empty();
        }
        com.sk89q.worldedit.world.World world = session.getSelectionWorld();
        if (world == null || !world.getName().equals(player.getWorld().getName())) {
            return Optional.empty();
        }
        try {
            Region region = session.getSelection(world);
            BlockVector3 min = region.getMinimumPoint();
            BlockVector3 max = region.getMaximumPoint();
            return Optional.of(new Box(player.getWorld().getName(), min.x(), min.y(), min.z(), max.x(), max.y(), max.z()));
        } catch (IncompleteRegionException exception) {
            return Optional.empty();
        }
    }
}
