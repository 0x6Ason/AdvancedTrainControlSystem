package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.Service;

import java.util.OptionalDouble;

/** The length of the track from a stop to the next, in blocks, where it is known. */
@FunctionalInterface
public interface RouteMeasure {

    OptionalDouble segmentBlocks(Service service, int fromIndex);

    RouteMeasure UNKNOWN = (service, fromIndex) -> OptionalDouble.empty();
}
