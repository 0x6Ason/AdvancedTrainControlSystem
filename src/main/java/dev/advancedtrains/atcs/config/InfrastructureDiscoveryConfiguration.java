package dev.advancedtrains.atcs.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Bounds automatic infrastructure suggestions without assigning operational semantics.
 */
public record InfrastructureDiscoveryConfiguration(
        StationEvidence stationEvidence,
        StationClassification stationClassification,
        StationGrouping stationGrouping,
        Confidence confidence,
        Rail rail) {

    public InfrastructureDiscoveryConfiguration {
        Objects.requireNonNull(stationEvidence, "stationEvidence");
        Objects.requireNonNull(stationClassification, "stationClassification");
        Objects.requireNonNull(stationGrouping, "stationGrouping");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(rail, "rail");
    }

    public record StationClassification(
            boolean platformRequiresArrivalEvidence,
            Set<String> reverseInstructions,
            Set<String> throughInstructions) {
        public StationClassification {
            reverseInstructions = normalizeInstructions(reverseInstructions, "reverseInstructions");
            throughInstructions = normalizeInstructions(throughInstructions, "throughInstructions");
            Set<String> overlap = new LinkedHashSet<>(reverseInstructions);
            overlap.retainAll(throughInstructions);
            if (!overlap.isEmpty()) {
                throw new IllegalArgumentException(
                        "Reverse and through station instructions must not overlap: "
                                + String.join(", ", overlap));
            }
        }
    }

    public record StationEvidence(
            List<String> arrivalPrefixes,
            int horizontalRadius,
            int verticalTolerance) {
        public StationEvidence {
            Objects.requireNonNull(arrivalPrefixes, "arrivalPrefixes");
            arrivalPrefixes = arrivalPrefixes.stream()
                    .filter(Objects::nonNull)
                    .map(String::strip)
                    .filter(value -> !value.isEmpty())
                    .distinct()
                    .toList();
            if (arrivalPrefixes.isEmpty()) {
                throw new IllegalArgumentException(
                        "infrastructure-discovery.station-evidence.arrival-prefixes must not be empty");
            }
            requireNonNegative(horizontalRadius,
                    "infrastructure-discovery.station-evidence.horizontal-radius");
            requireNonNegative(verticalTolerance,
                    "infrastructure-discovery.station-evidence.vertical-tolerance");
        }
    }

    public record StationGrouping(int horizontalRadius, int verticalTolerance) {
        public StationGrouping {
            requireNonNegative(horizontalRadius,
                    "infrastructure-discovery.station-grouping.horizontal-radius");
            requireNonNegative(verticalTolerance,
                    "infrastructure-discovery.station-grouping.vertical-tolerance");
        }
    }

    public record Confidence(
            double namedStation,
            double unnamedStation,
            double departurePoint,
            double trackControl) {
        public Confidence {
            requireProbability(namedStation,
                    "infrastructure-discovery.confidence.named-station");
            requireProbability(unnamedStation,
                    "infrastructure-discovery.confidence.unnamed-station");
            requireProbability(departurePoint,
                    "infrastructure-discovery.confidence.departure-point");
            requireProbability(trackControl,
                    "infrastructure-discovery.confidence.track-control");
        }
    }

    private static Set<String> normalizeInstructions(Set<String> values, String field) {
        Objects.requireNonNull(values, field);
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.strip().toLowerCase(java.util.Locale.ROOT));
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        return Set.copyOf(normalized);
    }

    public record Rail(
            Scope scope,
            int chunkSnapshotsPerTick,
            int blockChecksPerTick,
            int edgeNodesPerTick,
            int maximumNodes) {
        public Rail {
            Objects.requireNonNull(scope, "scope");
            requirePositive(chunkSnapshotsPerTick,
                    "infrastructure-discovery.rail.chunk-snapshots-per-tick");
            requirePositive(blockChecksPerTick,
                    "infrastructure-discovery.rail.block-checks-per-tick");
            requirePositive(edgeNodesPerTick,
                    "infrastructure-discovery.rail.edge-nodes-per-tick");
            requirePositive(maximumNodes, "infrastructure-discovery.rail.maximum-nodes");
        }

        public enum Scope {
            LOADED_CHUNKS,
            SAVED_CHUNKS
        }
    }

    private static void requireProbability(double value, String path) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(path + " must be between 0.0 and 1.0");
        }
    }

    private static void requireNonNegative(int value, String path) {
        if (value < 0) {
            throw new IllegalArgumentException(path + " must not be negative");
        }
    }

    private static void requirePositive(int value, String path) {
        if (value <= 0) {
            throw new IllegalArgumentException(path + " must be positive");
        }
    }
}
