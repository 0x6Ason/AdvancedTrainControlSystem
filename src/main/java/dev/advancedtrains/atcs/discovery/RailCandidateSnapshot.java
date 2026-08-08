package dev.advancedtrains.atcs.discovery;

import dev.advancedtrains.atcs.domain.BlockPosition;
import org.bukkit.block.data.Rail;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record RailCandidateSnapshot(
        UUID runId,
        Instant startedAt,
        Instant completedAt,
        List<Node> nodes,
        List<Edge> edges,
        String discoveryMode,
        int chunksConsidered,
        int chunksScanned,
        long blockChecks,
        int unloadedBoundaries,
        boolean nodeLimitReached) {

    public RailCandidateSnapshot {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(completedAt, "completedAt");
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
        discoveryMode = Objects.requireNonNull(discoveryMode, "discoveryMode");
        if (chunksConsidered < 0 || chunksScanned < 0 || chunksScanned > chunksConsidered) {
            throw new IllegalArgumentException("Chunk coverage counts are invalid");
        }
        if (blockChecks < 0) {
            throw new IllegalArgumentException("blockChecks must not be negative");
        }
        if (unloadedBoundaries < 0) {
            throw new IllegalArgumentException("unloadedBoundaries must not be negative");
        }
    }

    public record Node(
            UUID id,
            BlockPosition position,
            String materialName,
            Rail.Shape shape) {
    }

    public record Edge(UUID firstNodeId, UUID secondNodeId) {
        public Edge {
            Objects.requireNonNull(firstNodeId, "firstNodeId");
            Objects.requireNonNull(secondNodeId, "secondNodeId");
            if (firstNodeId.toString().compareTo(secondNodeId.toString()) >= 0) {
                throw new IllegalArgumentException("Rail edge node IDs must use canonical order");
            }
        }

        public static Edge of(UUID first, UUID second) {
            return first.toString().compareTo(second.toString()) < 0
                    ? new Edge(first, second)
                    : new Edge(second, first);
        }
    }

}
