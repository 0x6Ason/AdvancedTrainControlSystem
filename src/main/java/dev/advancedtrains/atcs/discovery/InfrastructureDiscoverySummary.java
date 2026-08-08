package dev.advancedtrains.atcs.discovery;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record InfrastructureDiscoverySummary(
        Optional<UUID> runId,
        Optional<Instant> completedAt,
        int sourceStationSigns,
        int sourceSpawnSigns,
        int stationCandidates,
        int platformCandidates,
        int departureCandidates,
        int trackControlCandidates) {

    public InfrastructureDiscoverySummary {
        runId = Objects.requireNonNull(runId, "runId");
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
    }

    public static InfrastructureDiscoverySummary empty() {
        return new InfrastructureDiscoverySummary(
                Optional.empty(), Optional.empty(), 0, 0, 0, 0, 0, 0);
    }

    public static InfrastructureDiscoverySummary from(InfrastructureCandidateSnapshot snapshot) {
        return new InfrastructureDiscoverySummary(
                Optional.of(snapshot.runId()),
                Optional.of(snapshot.completedAt()),
                snapshot.sourceStationSigns(),
                snapshot.sourceSpawnSigns(),
                snapshot.stations().size(),
                snapshot.platforms().size(),
                snapshot.departures().size(),
                snapshot.trackControls().size());
    }
}
