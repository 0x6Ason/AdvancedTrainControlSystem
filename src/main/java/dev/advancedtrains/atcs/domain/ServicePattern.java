package dev.advancedtrains.atcs.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record ServicePattern(
        DomainId id,
        DomainId routeVariantId,
        String displayName,
        int priorityClass,
        List<ServiceCall> calls) {

    public ServicePattern {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(routeVariantId, "routeVariantId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(calls, "calls");
        displayName = displayName.strip();
        calls = List.copyOf(calls);
        if (displayName.isEmpty()) {
            throw new IllegalArgumentException("Service pattern display name must not be blank");
        }
        if (calls.isEmpty()) {
            throw new IllegalArgumentException("A service pattern must define at least one call");
        }
        Set<Integer> visitIndexes = new HashSet<>();
        int previousIndex = -1;
        for (ServiceCall call : calls) {
            if (!visitIndexes.add(call.visitIndex())) {
                throw new IllegalArgumentException("Duplicate visit index: " + call.visitIndex());
            }
            if (call.visitIndex() <= previousIndex) {
                throw new IllegalArgumentException("Service calls must be ordered by visit index");
            }
            previousIndex = call.visitIndex();
        }
    }
}

