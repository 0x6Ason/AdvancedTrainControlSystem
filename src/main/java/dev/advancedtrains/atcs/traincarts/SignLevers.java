package dev.advancedtrains.atcs.traincarts;

import com.bergerkiller.bukkit.common.utils.BlockUtil;
import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.runtime.IndicatorService;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;

/**
 * Drives the levers attached to the block an indicator sign hangs on, the way TrainCarts signs
 * report through their levers.
 */
public final class SignLevers implements IndicatorService.Output {

    @Override
    public void set(BlockPos sign, boolean powered) {
        World world = Bukkit.getWorld(sign.world());
        if (world == null || !world.isChunkLoaded(sign.chunkX(), sign.chunkZ())) {
            return;
        }
        Block block = world.getBlockAt(sign.x(), sign.y(), sign.z());
        if (!(block.getState() instanceof Sign)) {
            return;
        }
        BlockUtil.setLeversAroundBlock(BlockUtil.getAttachedBlock(block), powered);
    }
}
