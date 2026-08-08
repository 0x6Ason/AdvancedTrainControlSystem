package dev.advancedtrains.atcs.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An exact directional path. A return journey is always a separate route variant.
 */
public record RouteVariant(
        DomainId id,
        DomainId lineId,
        String displayName,
        String directionName,
        Optional<String> destinationName,
        boolean circular,
        Optional<DomainId> anchorStationId,
        TerminalPolicy terminalPolicy,
        List<DomainId> segmentIds) {

    public RouteVariant {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(lineId, "lineId");
        displayName = requireText(displayName, "displayName");
        directionName = requireText(directionName, "directionName");
        Objects.requireNonNull(destinationName, "destinationName");
        destinationName = destinationName.map(value -> requireText(value, "destinationName"));
        Objects.requireNonNull(anchorStationId, "anchorStationId");
        Objects.requireNonNull(terminalPolicy, "terminalPolicy");
        Objects.requireNonNull(segmentIds, "segmentIds");
        segmentIds = List.copyOf(segmentIds);

        if (segmentIds.isEmpty()) {
            throw new IllegalArgumentException("A route variant must contain at least one physical segment");
        }
        for (int index = 1; index < segmentIds.size(); index++) {
            if (segmentIds.get(index - 1).equals(segmentIds.get(index))) {
                throw new IllegalArgumentException("A route cannot contain the same segment twice in succession");
            }
        }
        if (circular && anchorStationId.isEmpty()) {
            throw new IllegalArgumentException("A circular route requires an anchor station");
        }
        if (circular && terminalPolicy != TerminalPolicy.LOOP_CONTINUE) {
            throw new IllegalArgumentException("A circular route must use LOOP_CONTINUE terminal policy");
        }
        if (!circular && terminalPolicy == TerminalPolicy.LOOP_CONTINUE) {
            throw new IllegalArgumentException("Only a circular route can use LOOP_CONTINUE terminal policy");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        value = value.strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}

