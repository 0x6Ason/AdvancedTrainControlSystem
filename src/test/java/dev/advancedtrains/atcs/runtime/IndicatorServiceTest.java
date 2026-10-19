package dev.advancedtrains.atcs.runtime;

import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.operation.Indicator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static dev.advancedtrains.atcs.TestNetworks.network;
import static dev.advancedtrains.atcs.TestNetworks.service;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lamps and levers bound by command: asked for their state on every refresh (a lamp lit without
 * power goes out at the next block update beside it), retried while unloaded. Indicator signs'
 * levers are switched only when their state changes.
 */
class IndicatorServiceTest {
    private final Network network = network(2, service("a/east", "s1", "s2"));
    private final BlockPos lamp = BlockPos.parse("world,10,70,10");
    private final BlockPos sign = BlockPos.parse("world,20,70,10");
    private final List<String> calls = new ArrayList<>();
    private final Set<BlockPos> loaded = new HashSet<>(List.of(lamp, sign));
    private final IndicatorService.Output output = new IndicatorService.Output() {
        @Override
        public void set(BlockPos target, boolean powered) {
            calls.add("sign " + target.coordinates() + " " + powered);
        }

        @Override
        public boolean setBlock(BlockPos block, boolean powered) {
            if (!loaded.contains(block)) {
                return false;
            }
            calls.add("block " + block.coordinates() + " " + powered);
            return true;
        }
    };
    private final IndicatorService indicators = new IndicatorService(output);
    private final Set<String> occupied = new HashSet<>();

    private void refresh() {
        indicators.refresh(List.of(), network, occupied::contains);
    }

    @Test
    void boundBlocksFollowTheRuleAndAreAskedAgainEveryRefresh() {
        indicators.registerBlock(lamp, Indicator.parse("block tunnel", ""));
        indicators.register(sign, Indicator.parse("block tunnel", ""));
        assertEquals(2, indicators.size());
        refresh();
        assertEquals(List.of("sign 20 70 10 false", "block 10 70 10 false"), calls, "both cleared on the first refresh");
        calls.clear();
        refresh();
        assertEquals(List.of("block 10 70 10 false"), calls, "the sign is left; the block is asked again, in case it was changed");
        calls.clear();
        occupied.add("tunnel");
        refresh();
        assertEquals(List.of("sign 20 70 10 true", "block 10 70 10 true"), calls);
        assertEquals(2, indicators.lit());
    }

    @Test
    void aLampInAnUnloadedChunkIsSetOnceItsChunkIsBack() {
        indicators.registerBlock(lamp, Indicator.parse("block tunnel", ""));
        loaded.remove(lamp);
        occupied.add("tunnel");
        refresh();
        refresh();
        assertTrue(calls.isEmpty(), "not reachable yet");
        loaded.add(lamp);
        refresh();
        assertEquals(List.of("block 10 70 10 true"), calls, "lit as soon as the chunk is loaded");
        calls.clear();
        refresh();
        assertEquals(List.of("block 10 70 10 true"), calls, "and kept lit");
    }

    @Test
    void unbindingAndShutdownClearWhatWasLit() {
        indicators.registerBlock(lamp, Indicator.parse("block tunnel", ""));
        occupied.add("tunnel");
        refresh();
        calls.clear();
        indicators.unregisterBlock(lamp);
        assertEquals(List.of("block 10 70 10 false"), calls, "a lit lamp is cleared when unbound");
        assertEquals(0, indicators.size());
        calls.clear();
        indicators.registerBlock(lamp, Indicator.parse("block tunnel", ""));
        refresh();
        calls.clear();
        indicators.shutdown();
        assertEquals(List.of("block 10 70 10 false"), calls, "and at shutdown");
        assertEquals(0, indicators.size());
    }
}
