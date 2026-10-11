package dev.advancedtrains.atcs.network;

import java.util.List;

/**
 * Rules that assign trains to a service automatically, without an ATCS sign or command.
 *
 * <p>Every non-empty list must contain at least one value matching the train (AND across lists,
 * OR within a list). Comparisons ignore case. {@code trainNames} entries may use {@code *}.
 *
 * @param destinations TrainCarts destination names; matches the current destination or any
 *                     entry of the train's destination route
 * @param tags         TrainCarts tags
 * @param trainNames   TrainCarts train names, with {@code *} wildcards
 */
public record ServiceMatch(List<String> destinations, List<String> tags, List<String> trainNames) {
    public static final ServiceMatch NONE = new ServiceMatch(List.of(), List.of(), List.of());

    public ServiceMatch {
        destinations = clean(destinations);
        tags = clean(tags);
        trainNames = clean(trainNames);
    }

    public boolean isEmpty() {
        return destinations.isEmpty() && tags.isEmpty() && trainNames.isEmpty();
    }

    private static List<String> clean(List<String> values) {
        return values.stream().map(String::strip).filter(value -> !value.isEmpty()).toList();
    }
}
