package dev.advancedtrains.atcs.network;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * One entry in a service's stop list.
 *
 * <p>Text form: {@code station}, {@code station:platform}, and a leading {@code ~} for a station
 * the service runs through without stopping, for example {@code ~nerdy}.
 *
 * @param stationId  the station
 * @param platformId the planned platform, or {@code null} for any platform of the station
 * @param pass       {@code true} when the service runs through without stopping
 */
public record Stop(String stationId, @Nullable String platformId, boolean pass) {

    public Stop {
        stationId = Ids.normalize("station", stationId);
        if (platformId != null) {
            platformId = Ids.normalize("platform", platformId);
        }
    }

    public static Stop of(String stationId) {
        return new Stop(stationId, null, false);
    }

    public static Stop parse(String text) {
        Objects.requireNonNull(text, "text");
        String value = text.strip();
        boolean pass = value.startsWith("~");
        if (pass) {
            value = value.substring(1).strip();
        }
        int colon = value.indexOf(':');
        if (colon < 0) {
            return new Stop(value, null, pass);
        }
        return new Stop(value.substring(0, colon), value.substring(colon + 1), pass);
    }

    /** Whether a train observed at {@code platform} is at this stop. Any platform of the station counts. */
    public boolean isAt(PlatformRef platform) {
        return stationId.equals(platform.stationId());
    }

    public boolean stops() {
        return !pass;
    }

    public String format() {
        return (pass ? "~" : "") + stationId + (platformId == null ? "" : ":" + platformId);
    }

    @Override
    public String toString() {
        return format();
    }
}
