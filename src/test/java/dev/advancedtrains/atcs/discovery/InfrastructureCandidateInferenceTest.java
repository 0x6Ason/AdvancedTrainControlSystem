package dev.advancedtrains.atcs.discovery;

import dev.advancedtrains.atcs.config.InfrastructureDiscoveryConfiguration;
import dev.advancedtrains.atcs.domain.BlockPosition;
import dev.advancedtrains.atcs.legacy.LegacySignType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static dev.advancedtrains.atcs.discovery.InfrastructureCandidateSnapshot.TrackControlRole.REVERSING_CONTROL;
import static dev.advancedtrains.atcs.discovery.InfrastructureCandidateSnapshot.TrackControlRole.THROUGH_MOTION_CONTROL;
import static org.junit.jupiter.api.Assertions.assertEquals;

class InfrastructureCandidateInferenceTest {
    private final InfrastructureCandidateInference inference =
            new InfrastructureCandidateInference(configuration(true));

    @Test
    void separatesPassengerPlatformsFromBoostersAndReverseControls() {
        InfrastructureSourceSign passenger = sign(
                LegacySignType.STATION, 0, 64, 0, Map.of("instruction", "continue", "delay", "5"));
        InfrastructureSourceSign arrival = sign(
                LegacySignType.ANNOUNCE, 1, 64, 0,
                Map.of("announcement_text", "Arrived: Central Station"));
        InfrastructureSourceSign booster = sign(
                LegacySignType.STATION, 100, 64, 0,
                Map.of("instruction", "continue", "delay", "2"));
        InfrastructureSourceSign reverse = sign(
                LegacySignType.STATION, 200, 64, 0,
                Map.of("instruction", "reverse", "delay", "0"));
        InfrastructureSourceSign misleadingArrival = sign(
                LegacySignType.ANNOUNCE, 201, 64, 0,
                Map.of("announcement_text", "Arrived: Depot Terminus"));
        InfrastructureSourceSign spawn = sign(
                LegacySignType.SPAWN, 300, 64, 0,
                Map.of("spawn_pattern", "12m", "launch_force", "9999"));

        InfrastructureCandidateSnapshot snapshot = inference.infer(
                UUID.randomUUID(),
                Optional.empty(),
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1),
                List.of(passenger, arrival, booster, reverse, misleadingArrival, spawn));

        assertEquals(1, snapshot.stations().size());
        assertEquals("Central Station", snapshot.stations().getFirst().suggestedName().orElseThrow());
        assertEquals(1, snapshot.platforms().size());
        assertEquals(1, snapshot.departures().size());
        assertEquals(2, snapshot.trackControls().size());
        assertEquals(Set.of(THROUGH_MOTION_CONTROL, REVERSING_CONTROL),
                snapshot.trackControls().stream()
                        .map(InfrastructureCandidateSnapshot.TrackControlCandidate::suggestedRole)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void canBeConfiguredToSuggestUnnamedPlatforms() {
        InfrastructureCandidateInference permissive =
                new InfrastructureCandidateInference(configuration(false));
        InfrastructureSourceSign station = sign(
                LegacySignType.STATION, 0, 64, 0, Map.of("instruction", "continue"));

        InfrastructureCandidateSnapshot snapshot = permissive.infer(
                UUID.randomUUID(), Optional.empty(), Instant.EPOCH, Instant.EPOCH, List.of(station));

        assertEquals(1, snapshot.stations().size());
        assertEquals(1, snapshot.platforms().size());
        assertEquals(0, snapshot.trackControls().size());
    }

    private static InfrastructureDiscoveryConfiguration configuration(boolean requireEvidence) {
        return new InfrastructureDiscoveryConfiguration(
                new InfrastructureDiscoveryConfiguration.StationEvidence(
                        List.of("Arrived:", "Arrive:"), 16, 4),
                new InfrastructureDiscoveryConfiguration.StationClassification(
                        requireEvidence, true, Set.of("reverse"), Set.of("continue")),
                new InfrastructureDiscoveryConfiguration.StationGrouping(48, 12),
                new InfrastructureDiscoveryConfiguration.Confidence(0.9, 0.4, 0.85, 0.7),
                new InfrastructureDiscoveryConfiguration.Rail(
                        InfrastructureDiscoveryConfiguration.Rail.Scope.SAVED_CHUNKS,
                        2,
                        16384,
                        4096,
                        500000));
    }

    private static InfrastructureSourceSign sign(
            LegacySignType type,
            int x,
            int y,
            int z,
            Map<String, String> attributes) {
        return new InfrastructureSourceSign(
                UUID.randomUUID(), type, new BlockPosition("world", x, y, z), attributes);
    }
}
