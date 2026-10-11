package dev.advancedtrains.atcs.map;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.operation.TrackPaths;

import java.util.List;

/** A web map that shows the network and live trains. */
public interface MapLayer {

    String name();

    /** Whether the map plugin has finished loading and accepts markers. */
    boolean ready();

    /** Redraws stations and lines. */
    void showNetwork(Network network, TrackPaths paths, StationLocator locator);

    /** Replaces the live train markers. */
    void showTrains(List<TrainMarker> trains);

    void close();

    /**
     * @param id     stable train identifier
     * @param label  short text, e.g. "A Local » New Village St."
     * @param detail longer plain text, e.g. "Next: Nerdy St."
     * @param color  line colour
     */
    record TrainMarker(String id, String world, double x, double y, double z, String label, String detail, int color) {
    }
}
