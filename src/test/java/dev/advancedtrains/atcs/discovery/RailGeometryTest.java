package dev.advancedtrains.atcs.discovery;

import org.bukkit.block.data.Rail;
import org.junit.jupiter.api.Test;

import static dev.advancedtrains.atcs.discovery.RailGeometry.Direction.EAST;
import static dev.advancedtrains.atcs.discovery.RailGeometry.Direction.NORTH;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RailGeometryTest {
    @Test
    void ascendingRailConnectsToFlatRailAtItsHighEnd() {
        assertTrue(RailGeometry.connects(
                Rail.Shape.ASCENDING_EAST, 64,
                Rail.Shape.EAST_WEST, 65,
                EAST));
    }

    @Test
    void flatRailConnectsDownToTheHighEndOfAnAscendingNeighbor() {
        assertTrue(RailGeometry.connects(
                Rail.Shape.EAST_WEST, 65,
                Rail.Shape.ASCENDING_EAST, 64,
                RailGeometry.Direction.WEST));
    }

    @Test
    void rejectsParallelAndVerticallyMisalignedRails() {
        assertFalse(RailGeometry.connects(
                Rail.Shape.NORTH_SOUTH, 64,
                Rail.Shape.EAST_WEST, 64,
                NORTH));
        assertFalse(RailGeometry.connects(
                Rail.Shape.ASCENDING_EAST, 64,
                Rail.Shape.EAST_WEST, 64,
                EAST));
    }
}
