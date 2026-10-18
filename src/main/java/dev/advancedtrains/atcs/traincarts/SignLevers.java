package dev.advancedtrains.atcs.traincarts;

import com.bergerkiller.bukkit.common.utils.BlockUtil;
import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.runtime.IndicatorService;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Powerable;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.BlockData;

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

    /**
     * A bound lamp is lit or cleared by its block state; a bound lever is thrown, which powers
     * what it is wired to. Anything else is left alone (the command refuses to bind it, and
     * {@code /atcs output list} says when it is gone).
     */
    @Override
    public boolean setBlock(BlockPos position, boolean powered) {
        World world = Bukkit.getWorld(position.world());
        if (world == null || !world.isChunkLoaded(position.chunkX(), position.chunkZ())) {
            return false;
        }
        Block block = world.getBlockAt(position.x(), position.y(), position.z());
        BlockData data = block.getBlockData();
        if (data instanceof Lightable lamp) {
            if (lamp.isLit() != powered) {
                lamp.setLit(powered);
                block.setBlockData(lamp, false);
            }
        } else if (data instanceof Powerable lever) {
            if (lever.isPowered() != powered) {
                lever.setPowered(powered);
                block.setBlockData(lever, true);
            }
        }
        return true;
    }
}
