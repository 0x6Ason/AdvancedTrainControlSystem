package dev.advancedtrains.atcs.map;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.operation.RailGraph;
import dev.advancedtrains.atcs.operation.TrackPaths;
import org.jspecify.annotations.Nullable;

import java.util.List;

/** A web map that shows the network and live trains. */
public interface MapLayer {

    String name();

    /** Whether the map plugin has finished loading and accepts markers. */
    boolean ready();

    /** Redraws stations, lines and the track itself. */
    void showNetwork(Network network, TrackPaths paths, RailGraph graph, StationLocator locator);

    /** Whether the network should be drawn again although nothing changed, for example once the map plugin has come up. */
    default boolean wantsNetwork() {
        return false;
    }

    /** Replaces the live train markers and refreshes what station markers say about departures. */
    void showLive(List<TrainMarker> trains, List<StationLive> stations);

    void close();

    /**
     * @param id        stable train identifier
     * @param label     short text, e.g. "A Local » New Village St."
     * @param detail    longer plain text, e.g. "Next: Nerdy St. in 1:05"
     * @param color     line colour
     * @param lineId    the line, for its badge
     * @param held      the train is standing still between stations or kept at a platform
     */
    record TrainMarker(String id, String world, double x, double y, double z, String label, String detail, int color,
                       String lineId, boolean held) {
    }

    /** One departure as a station popup shows it. */
    record DepartureLine(String lineBadge, int lineColor, String destination, @Nullable String platform, String time) {
    }

    /** The next departures of a station. */
    record StationLive(String stationId, List<DepartureLine> departures) {
    }
}
