package dev.advancedtrains.atcs.discovery;

import dev.advancedtrains.atcs.domain.BlockPosition;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record InfrastructureCandidateSnapshot(
        UUID runId,
        Optional<UUID> sourceScanId,
        Instant startedAt,
        Instant completedAt,
        int sourceStationSigns,
        int sourceSpawnSigns,
        List<StationCandidate> stations,
        List<PlatformCandidate> platforms,
        List<DepartureCandidate> departures,
        List<TrackControlCandidate> trackControls,
        List<Evidence> evidence) {

    public InfrastructureCandidateSnapshot {
        Objects.requireNonNull(runId, "runId");
        sourceScanId = Objects.requireNonNull(sourceScanId, "sourceScanId");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(completedAt, "completedAt");
        stations = List.copyOf(Objects.requireNonNull(stations, "stations"));
        platforms = List.copyOf(Objects.requireNonNull(platforms, "platforms"));
        departures = List.copyOf(Objects.requireNonNull(departures, "departures"));
        trackControls = List.copyOf(Objects.requireNonNull(trackControls, "trackControls"));
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
    }

    public record StationCandidate(
            UUID id,
            Optional<String> suggestedName,
            Optional<String> normalizedName,
            BlockPosition center,
            double confidence) {
    }

    public record PlatformCandidate(
            UUID id,
            UUID stationCandidateId,
            UUID sourceSignId,
            String suggestedLabel,
            BlockPosition position,
            double confidence) {
    }

    public record DepartureCandidate(
            UUID id,
            UUID sourceSignId,
            BlockPosition position,
            Optional<String> directionHint,
            Optional<String> spawnPattern,
            Optional<String> interval,
            Optional<Double> launchForce,
            Optional<Boolean> alwaysPowered,
            double confidence) {
    }

    public record TrackControlCandidate(
            UUID id,
            UUID sourceSignId,
            BlockPosition position,
            TrackControlRole suggestedRole,
            Optional<String> instruction,
            Optional<String> delay,
            Optional<String> launchDistance,
            Optional<String> centerOffset,
            double confidence) {
    }

    public record Evidence(
            CandidateType candidateType,
            UUID candidateId,
            EvidenceKind kind,
            UUID sourceSignId,
            Optional<Double> distanceBlocks,
            Optional<String> evidenceText) {
    }

    public enum CandidateType {
        STATION,
        PLATFORM,
        DEPARTURE,
        TRACK_CONTROL
    }

    public enum EvidenceKind {
        STATION_SIGN,
        ARRIVAL_ANNOUNCEMENT,
        SPAWN_SIGN
    }

    public enum TrackControlRole {
        REVERSING_CONTROL,
        THROUGH_MOTION_CONTROL,
        UNCLASSIFIED_STATION_CONTROL
    }
}
