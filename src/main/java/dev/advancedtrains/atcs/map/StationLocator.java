package dev.advancedtrains.atcs.map;

import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Station;
import dev.advancedtrains.atcs.network.Stop;

import java.util.Optional;

/** Where stations and stops are in the world, including positions learned from ATCS stop signs. */
public interface StationLocator {

    Optional<BlockPos> station(Station station);

    /** The position of a stop: its planned platform if known, otherwise the station. */
    Optional<BlockPos> stop(Station station, Stop stop);
}
