package dev.advancedtrains.atcs.domain;

import java.util.Objects;

public record TrackNode(DomainId id, BlockPosition position, Type type) {
    public TrackNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(type, "type");
    }

    public enum Type {
        JUNCTION,
        ENDPOINT,
        STATION_THROAT,
        DEPOT_CONNECTION,
        TOPOLOGY_BOUNDARY
    }
}

