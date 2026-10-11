package dev.advancedtrains.atcs.operation;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The parts of a TrainCarts train that decide which service it runs.
 *
 * @param name             TrainCarts train name, stable while the train exists
 * @param tags             TrainCarts tags
 * @param destination      current TrainCarts destination, empty when none
 * @param destinationRoute TrainCarts destination route, empty when none
 */
public record TrainInfo(String name, Set<String> tags, String destination, List<String> destinationRoute) {

    public TrainInfo {
        Objects.requireNonNull(name, "name");
        tags = Set.copyOf(tags);
        destination = Objects.requireNonNullElse(destination, "");
        destinationRoute = List.copyOf(destinationRoute);
    }

    public static TrainInfo of(String name, Collection<String> tags, String destination, List<String> route) {
        return new TrainInfo(name, Set.copyOf(tags), destination, route);
    }
}
