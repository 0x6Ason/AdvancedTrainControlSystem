package dev.advancedtrains.atcs.domain;

import java.util.List;
import java.util.Objects;

/**
 * One directed physical edge. Reverse travel, when allowed, is represented by another segment.
 */
public record TrackSegment(
        DomainId id,
        DomainId fromNodeId,
        DomainId toNodeId,
        double lengthBlocks,
        double speedLimitBlocksPerSecond,
        List<BlockPosition> geometry) {

    public TrackSegment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(fromNodeId, "fromNodeId");
        Objects.requireNonNull(toNodeId, "toNodeId");
        Objects.requireNonNull(geometry, "geometry");
        geometry = List.copyOf(geometry);
        if (fromNodeId.equals(toNodeId)) {
            throw new IllegalArgumentException("A directed track segment must connect two different nodes");
        }
        if (!Double.isFinite(lengthBlocks) || lengthBlocks <= 0) {
            throw new IllegalArgumentException("Track segment length must be finite and positive");
        }
        if (!Double.isFinite(speedLimitBlocksPerSecond) || speedLimitBlocksPerSecond <= 0) {
            throw new IllegalArgumentException("Track segment speed limit must be finite and positive");
        }
    }
}

