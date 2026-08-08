package dev.advancedtrains.atcs.discovery;

import dev.advancedtrains.atcs.config.InfrastructureDiscoveryConfiguration;
import dev.advancedtrains.atcs.domain.BlockPosition;
import dev.advancedtrains.atcs.discovery.InfrastructureCandidateSnapshot.TrackControlRole;
import dev.advancedtrains.atcs.legacy.LegacySignType;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static dev.advancedtrains.atcs.discovery.InfrastructureCandidateSnapshot.CandidateType;
import static dev.advancedtrains.atcs.discovery.InfrastructureCandidateSnapshot.EvidenceKind;

public final class InfrastructureCandidateInference {
    private final InfrastructureDiscoveryConfiguration configuration;
    private final StationNameExtractor nameExtractor;

    public InfrastructureCandidateInference(InfrastructureDiscoveryConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.nameExtractor = new StationNameExtractor(
                configuration.stationEvidence().arrivalPrefixes());
    }

    public InfrastructureCandidateSnapshot infer(
            UUID runId,
            Optional<UUID> sourceScanId,
            Instant startedAt,
            Instant completedAt,
            List<InfrastructureSourceSign> signs) {
        Objects.requireNonNull(signs, "signs");
        List<NamedEvidence> announcements = signs.stream()
                .filter(sign -> sign.type() == LegacySignType.ANNOUNCE)
                .map(this::extractEvidence)
                .flatMap(Optional::stream)
                .toList();
        List<StationSite> stationSites = signs.stream()
                .filter(sign -> sign.type() == LegacySignType.STATION)
                .map(sign -> new StationSite(sign, nearestEvidence(sign, announcements)))
                .toList();
        List<InfrastructureSourceSign> spawnSigns = signs.stream()
                .filter(sign -> sign.type() == LegacySignType.SPAWN)
                .toList();

        List<StationSite> passengerSites = stationSites.stream()
                .filter(this::isPassengerPlatformCandidate)
                .toList();
        List<StationSite> controlSites = stationSites.stream()
                .filter(site -> !isPassengerPlatformCandidate(site))
                .toList();
        List<List<StationSite>> groups = groupStations(passengerSites);
        List<InfrastructureCandidateSnapshot.StationCandidate> stations = new ArrayList<>();
        List<InfrastructureCandidateSnapshot.PlatformCandidate> platforms = new ArrayList<>();
        List<InfrastructureCandidateSnapshot.DepartureCandidate> departures = new ArrayList<>();
        List<InfrastructureCandidateSnapshot.TrackControlCandidate> trackControls = new ArrayList<>();
        List<InfrastructureCandidateSnapshot.Evidence> evidence = new ArrayList<>();

        for (List<StationSite> group : groups) {
            group.sort(Comparator.comparing((StationSite site) -> site.sign().position().worldName())
                    .thenComparingInt(site -> site.sign().position().x())
                    .thenComparingInt(site -> site.sign().position().y())
                    .thenComparingInt(site -> site.sign().position().z())
                    .thenComparing(site -> site.sign().id()));
            Optional<NamedEvidence> groupName = group.stream()
                    .map(StationSite::nameEvidence)
                    .flatMap(Optional::stream)
                    .findFirst();
            BlockPosition center = center(group);
            UUID stationId = stableId(runId, "station", group.stream()
                    .map(site -> site.sign().id().toString())
                    .sorted()
                    .toList());
            double stationConfidence = groupName.isPresent()
                    ? configuration.confidence().namedStation()
                    : configuration.confidence().unnamedStation();
            stations.add(new InfrastructureCandidateSnapshot.StationCandidate(
                    stationId,
                    groupName.map(NamedEvidence::name),
                    groupName.map(NamedEvidence::normalizedName),
                    center,
                    stationConfidence));

            int platformNumber = 1;
            for (StationSite site : group) {
                UUID platformId = stableId(runId, "platform", List.of(site.sign().id().toString()));
                double platformConfidence = site.nameEvidence().isPresent()
                        ? configuration.confidence().namedStation()
                        : configuration.confidence().unnamedStation();
                platforms.add(new InfrastructureCandidateSnapshot.PlatformCandidate(
                        platformId,
                        stationId,
                        site.sign().id(),
                        "P" + platformNumber++,
                        site.sign().position(),
                        platformConfidence));
                evidence.add(new InfrastructureCandidateSnapshot.Evidence(
                        CandidateType.PLATFORM,
                        platformId,
                        EvidenceKind.STATION_SIGN,
                        site.sign().id(),
                        Optional.of(0.0),
                        Optional.empty()));
                evidence.add(new InfrastructureCandidateSnapshot.Evidence(
                        CandidateType.STATION,
                        stationId,
                        EvidenceKind.STATION_SIGN,
                        site.sign().id(),
                        Optional.of(distance(site.sign().position(), center)),
                        Optional.empty()));
                site.nameEvidence().ifPresent(named -> {
                    evidence.add(new InfrastructureCandidateSnapshot.Evidence(
                            CandidateType.PLATFORM,
                            platformId,
                            EvidenceKind.ARRIVAL_ANNOUNCEMENT,
                            named.sign().id(),
                            Optional.of(named.distance()),
                            Optional.of(named.name())));
                    evidence.add(new InfrastructureCandidateSnapshot.Evidence(
                            CandidateType.STATION,
                            stationId,
                            EvidenceKind.ARRIVAL_ANNOUNCEMENT,
                            named.sign().id(),
                            Optional.of(named.distance()),
                            Optional.of(named.name())));
                });
            }
        }

        for (StationSite site : controlSites) {
            UUID controlId = stableId(runId, "track-control", List.of(site.sign().id().toString()));
            Map<String, String> attributes = site.sign().attributes();
            trackControls.add(new InfrastructureCandidateSnapshot.TrackControlCandidate(
                    controlId,
                    site.sign().id(),
                    site.sign().position(),
                    suggestedControlRole(site.sign()),
                    optionalText(attributes.get("instruction")),
                    optionalText(attributes.get("delay")),
                    optionalText(attributes.get("launch_distance")),
                    optionalText(attributes.get("center_offset")),
                    configuration.confidence().trackControl()));
            evidence.add(new InfrastructureCandidateSnapshot.Evidence(
                    CandidateType.TRACK_CONTROL,
                    controlId,
                    EvidenceKind.STATION_SIGN,
                    site.sign().id(),
                    Optional.of(0.0),
                    optionalText(attributes.get("instruction"))));
        }

        for (InfrastructureSourceSign spawn : spawnSigns) {
            UUID departureId = stableId(runId, "departure", List.of(spawn.id().toString()));
            Map<String, String> attributes = spawn.attributes();
            departures.add(new InfrastructureCandidateSnapshot.DepartureCandidate(
                    departureId,
                    spawn.id(),
                    spawn.position(),
                    cleanDirection(attributes.get("header_options")),
                    optionalText(attributes.get("spawn_pattern")),
                    optionalText(attributes.get("interval")),
                    parseDouble(attributes.get("launch_force")),
                    parseBoolean(attributes.get("always_powered")),
                    configuration.confidence().departurePoint()));
            evidence.add(new InfrastructureCandidateSnapshot.Evidence(
                    CandidateType.DEPARTURE,
                    departureId,
                    EvidenceKind.SPAWN_SIGN,
                    spawn.id(),
                    Optional.of(0.0),
                    optionalText(attributes.get("spawn_pattern"))));
        }

        return new InfrastructureCandidateSnapshot(
                runId,
                sourceScanId,
                startedAt,
                completedAt,
                stationSites.size(),
                spawnSigns.size(),
                stations,
                platforms,
                departures,
                trackControls,
                evidence);
    }

    private boolean isPassengerPlatformCandidate(StationSite site) {
        var classification = configuration.stationClassification();
        if (classification.reverseInstructionImpliesTrackControl()
                && instructionMatches(site.sign(), classification.reverseInstructions())) {
            return false;
        }
        return site.nameEvidence().isPresent()
                || !classification.platformRequiresArrivalEvidence();
    }

    private TrackControlRole suggestedControlRole(InfrastructureSourceSign sign) {
        var classification = configuration.stationClassification();
        if (instructionMatches(sign, classification.reverseInstructions())) {
            return TrackControlRole.REVERSING_CONTROL;
        }
        if (instructionMatches(sign, classification.throughInstructions())) {
            return TrackControlRole.THROUGH_MOTION_CONTROL;
        }
        return TrackControlRole.UNCLASSIFIED_STATION_CONTROL;
    }

    private static boolean instructionMatches(InfrastructureSourceSign sign, java.util.Set<String> values) {
        return optionalText(sign.attributes().get("instruction"))
                .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                .filter(values::contains)
                .isPresent();
    }

    private Optional<NamedEvidence> extractEvidence(InfrastructureSourceSign sign) {
        String text = sign.attributes().get("announcement_text");
        return nameExtractor.extract(text).map(name -> new NamedEvidence(
                sign, name, StationNameExtractor.normalizeName(name), 0.0));
    }

    private Optional<NamedEvidence> nearestEvidence(
            InfrastructureSourceSign station,
            List<NamedEvidence> announcements) {
        var limits = configuration.stationEvidence();
        return announcements.stream()
                .filter(evidence -> sameWorld(station.position(), evidence.sign().position()))
                .filter(evidence -> Math.abs(station.position().y() - evidence.sign().position().y())
                        <= limits.verticalTolerance())
                .filter(evidence -> horizontalDistanceSquared(
                        station.position(), evidence.sign().position())
                        <= (long) limits.horizontalRadius() * limits.horizontalRadius())
                .map(evidence -> evidence.withDistance(distance(
                        station.position(), evidence.sign().position())))
                .min(Comparator.comparingDouble(NamedEvidence::distance)
                        .thenComparing(evidence -> evidence.sign().id()));
    }

    private List<List<StationSite>> groupStations(List<StationSite> sites) {
        int[] parent = new int[sites.size()];
        for (int index = 0; index < parent.length; index++) {
            parent[index] = index;
        }
        var limits = configuration.stationGrouping();
        long radiusSquared = (long) limits.horizontalRadius() * limits.horizontalRadius();
        for (int first = 0; first < sites.size(); first++) {
            for (int second = first + 1; second < sites.size(); second++) {
                StationSite a = sites.get(first);
                StationSite b = sites.get(second);
                if (!sameWorld(a.sign().position(), b.sign().position())
                        || Math.abs(a.sign().position().y() - b.sign().position().y())
                        > limits.verticalTolerance()
                        || horizontalDistanceSquared(a.sign().position(), b.sign().position()) > radiusSquared
                        || !compatibleNames(a.nameEvidence(), b.nameEvidence())) {
                    continue;
                }
                union(parent, first, second);
            }
        }
        Map<Integer, List<StationSite>> groups = new LinkedHashMap<>();
        for (int index = 0; index < sites.size(); index++) {
            groups.computeIfAbsent(find(parent, index), ignored -> new ArrayList<>()).add(sites.get(index));
        }
        return new ArrayList<>(groups.values());
    }

    private static boolean compatibleNames(Optional<NamedEvidence> first, Optional<NamedEvidence> second) {
        if (first.isEmpty() && second.isEmpty()) {
            return true;
        }
        return first.isPresent() && second.isPresent()
                && first.orElseThrow().normalizedName().equals(second.orElseThrow().normalizedName());
    }

    private static BlockPosition center(List<StationSite> group) {
        String world = group.getFirst().sign().position().worldName();
        int x = (int) Math.round(group.stream().mapToInt(site -> site.sign().position().x()).average().orElseThrow());
        int y = (int) Math.round(group.stream().mapToInt(site -> site.sign().position().y()).average().orElseThrow());
        int z = (int) Math.round(group.stream().mapToInt(site -> site.sign().position().z()).average().orElseThrow());
        return new BlockPosition(world, x, y, z);
    }

    private static Optional<String> cleanDirection(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String cleaned = value.strip().replaceFirst("^:+", "").strip();
        return cleaned.isEmpty() ? Optional.empty() : Optional.of(cleaned);
    }

    private static Optional<String> optionalText(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.strip());
    }

    private static Optional<Double> parseDouble(String value) {
        try {
            return optionalText(value).map(Double::parseDouble);
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private static Optional<Boolean> parseBoolean(String value) {
        return optionalText(value).map(Boolean::parseBoolean);
    }

    private static boolean sameWorld(BlockPosition first, BlockPosition second) {
        return first.worldName().equalsIgnoreCase(second.worldName());
    }

    private static long horizontalDistanceSquared(BlockPosition first, BlockPosition second) {
        long dx = (long) first.x() - second.x();
        long dz = (long) first.z() - second.z();
        return dx * dx + dz * dz;
    }

    private static double distance(BlockPosition first, BlockPosition second) {
        long dx = (long) first.x() - second.x();
        long dy = (long) first.y() - second.y();
        long dz = (long) first.z() - second.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static UUID stableId(UUID runId, String kind, List<String> sources) {
        String identity = runId + ":" + kind + ":" + String.join(",", sources);
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    private static int find(int[] parent, int index) {
        if (parent[index] != index) {
            parent[index] = find(parent, parent[index]);
        }
        return parent[index];
    }

    private static void union(int[] parent, int first, int second) {
        int firstRoot = find(parent, first);
        int secondRoot = find(parent, second);
        if (firstRoot != secondRoot) {
            parent[secondRoot] = firstRoot;
        }
    }

    private record StationSite(
            InfrastructureSourceSign sign,
            Optional<NamedEvidence> nameEvidence) {
    }

    private record NamedEvidence(
            InfrastructureSourceSign sign,
            String name,
            String normalizedName,
            double distance) {
        private NamedEvidence withDistance(double newDistance) {
            return new NamedEvidence(sign, name, normalizedName, newDistance);
        }
    }
}
