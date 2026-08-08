package dev.advancedtrains.atcs.discovery;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record RailDiscoverySummary(
        Optional<UUID> runId,
        Optional<Instant> completedAt,
        String discoveryMode,
        int chunksConsidered,
        int chunksScanned,
        long blockChecks,
        int nodesDiscovered,
        int edgesDiscovered,
        int unloadedBoundaries,
        boolean nodeLimitReached) {

    public RailDiscoverySummary {
        runId = Objects.requireNonNull(runId, "runId");
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
        discoveryMode = Objects.requireNonNull(discoveryMode, "discoveryMode");
    }

    public static RailDiscoverySummary empty() {
        return new RailDiscoverySummary(
                Optional.empty(), Optional.empty(), "NONE", 0, 0, 0, 0, 0, 0, false);
    }

    public static RailDiscoverySummary from(RailCandidateSnapshot snapshot) {
        return new RailDiscoverySummary(
                Optional.of(snapshot.runId()),
                Optional.of(snapshot.completedAt()),
                snapshot.discoveryMode(),
                snapshot.chunksConsidered(),
                snapshot.chunksScanned(),
                snapshot.blockChecks(),
                snapshot.nodes().size(),
                snapshot.edges().size(),
                snapshot.unloadedBoundaries(),
                snapshot.nodeLimitReached());
    }
}
