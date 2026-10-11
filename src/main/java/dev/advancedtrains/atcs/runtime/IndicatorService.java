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

    /** Switches the output of a sign: the levers on the block it hangs on. */
    public interface Output {
        void set(BlockPos sign, boolean powered);
    }

    private final Output output;
    private final Map<BlockPos, Indicator> signs = new LinkedHashMap<>();
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

    public int size() {
        return signs.size();
    }

    public long lit() {
        return applied.values().stream().filter(Boolean::booleanValue).count();
    }

    /** Lights or clears every sign according to the journeys; only changed outputs are touched. */
    public void refresh(Collection<JourneySnapshot> journeys, Network network) {
        for (Map.Entry<BlockPos, Indicator> entry : signs.entrySet()) {
            boolean active = entry.getValue().active(journeys, network);
            Boolean previous = applied.put(entry.getKey(), active);
            if (previous == null || previous != active) {
                output.set(entry.getKey(), active);
            }
        }
    }

    /** Clears every lit output, for shutdown. */
    public void shutdown() {
        for (Map.Entry<BlockPos, Boolean> entry : applied.entrySet()) {
            if (entry.getValue()) {
                output.set(entry.getKey(), false);
            }
        }
        applied.clear();
        signs.clear();
    }
}
