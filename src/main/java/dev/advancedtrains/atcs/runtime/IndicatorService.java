package dev.advancedtrains.atcs.runtime;

import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.operation.Indicator;
import dev.advancedtrains.atcs.operation.JourneySnapshot;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code atcs indicator} signs in loaded chunks and the redstone they drive. Signs come and go
 * with their chunks; each one is switched only when its state changes.
 */
public final class IndicatorService {

    /** Switches the output of a sign (the levers on the block it hangs on) or a bound lamp or lever. */
    public interface Output {
        void set(BlockPos sign, boolean powered);

        /** Lights or clears a bound lamp or lever; false when it could not be reached (chunk not loaded). */
        default boolean setBlock(BlockPos block, boolean powered) {
            return true;
        }
    }

    private final Output output;
    private final Map<BlockPos, Indicator> signs = new LinkedHashMap<>();
    /** Lamps and levers bound by command, driven directly. */
    private final Map<BlockPos, Indicator> blocks = new LinkedHashMap<>();
    private final Map<BlockPos, Boolean> applied = new HashMap<>();

    public IndicatorService(Output output) {
        this.output = output;
    }

    /** Adds or replaces a sign; its output is set at the next refresh. */
    public void register(BlockPos sign, Indicator indicator) {
        signs.put(sign, indicator);
        applied.remove(sign);
    }

    public void unregister(BlockPos sign) {
        signs.remove(sign);
        applied.remove(sign);
    }

    /** Binds (or rebinds) a lamp or lever; set at the next refresh, and again whenever its chunk is loaded. */
    public void registerBlock(BlockPos block, Indicator indicator) {
        blocks.put(block, indicator);
        applied.remove(block);
    }

    public void unregisterBlock(BlockPos block) {
        if (blocks.remove(block) != null && Boolean.TRUE.equals(applied.remove(block))) {
            output.setBlock(block, false);
        }
    }

    public java.util.Set<BlockPos> blocks() {
        return java.util.Set.copyOf(blocks.keySet());
    }

    public int size() {
        return signs.size() + blocks.size();
    }

    public long lit() {
        return applied.values().stream().filter(Boolean::booleanValue).count();
    }

    /** Lights or clears every sign according to the journeys; only changed outputs are touched. */
    public void refresh(Collection<JourneySnapshot> journeys, Network network) {
        refresh(journeys, network, block -> false);
    }

    /** As {@link #refresh(Collection, Network)}, with the virtual blocks' state for block indicators. */
    public void refresh(Collection<JourneySnapshot> journeys, Network network, java.util.function.Predicate<String> blockOccupied) {
        for (Map.Entry<BlockPos, Indicator> entry : signs.entrySet()) {
            boolean active = entry.getValue().active(journeys, network, blockOccupied);
            Boolean previous = applied.put(entry.getKey(), active);
            if (previous == null || previous != active) {
                output.set(entry.getKey(), active);
            }
        }
        for (Map.Entry<BlockPos, Indicator> entry : blocks.entrySet()) {
            boolean active = entry.getValue().active(journeys, network, blockOccupied);
            Boolean previous = applied.get(entry.getKey());
            if (previous != null && previous == active) {
                continue;
            }
            // A block in an unloaded chunk is tried again next time; nobody sees it meanwhile.
            if (output.setBlock(entry.getKey(), active)) {
                applied.put(entry.getKey(), active);
            }
        }
    }

    /** Clears every lit output, for shutdown. */
    public void shutdown() {
        for (Map.Entry<BlockPos, Boolean> entry : applied.entrySet()) {
            if (entry.getValue()) {
                if (blocks.containsKey(entry.getKey())) {
                    output.setBlock(entry.getKey(), false);
                } else {
                    output.set(entry.getKey(), false);
                }
            }
        }
        applied.clear();
        signs.clear();
        blocks.clear();
    }
}
