package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.Ids;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.network.Stop;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Locale;

/**
 * What an {@code atcs indicator} sign watches, so the levers on it can drive lamps, track diagrams
 * or barriers: trains standing at a station or platform, approaching it, running from it to their
 * next stop, or running between it and another station. Optionally only trains of one line or one
 * service count.
 *
 * @param stationId      the station watched
 * @param platformId     one platform of it, or null for any
 * @param otherStationId the far end of the stretch of track, for {@link Mode#TO} and
 *                       {@link Mode#BETWEEN}; null otherwise
 * @param lineId         only trains of this line, or null
 * @param service        only trains of this service, or null
 */
public record Indicator(Mode mode, String stationId, @Nullable String platformId, @Nullable String otherStationId,
                        @Nullable String lineId, @Nullable ServiceKey service) {

    public enum Mode {
        /** A train stands at (or runs through) the station or platform. */
        AT,
        /** A train has been announced as approaching the station. */
        APPROACH,
        /** A train has left the station or platform and has not reached its next stop. */
        AFTER,
        /** A train has left the station and its next stop is the other station. */
        TO,
        /** A train runs between the two stations, in either direction. */
        BETWEEN
    }

    /**
     * Parses sign lines 3 and 4: {@code <station>[:<platform>]} and
     * {@code [at|approach|after|to <station>|between <station>] [<line>|<line/service>]}.
     *
     * @throws IllegalArgumentException with a player-readable message when the text is invalid
     */
    public static Indicator parse(String third, String fourth) {
        String target = third.strip();
        if (target.isEmpty()) {
            throw new IllegalArgumentException("Line 3 must name the station to watch, for example 'main' or 'main:2'");
        }
        int colon = target.indexOf(':');
        String station = Ids.normalize("station", colon < 0 ? target : target.substring(0, colon));
        String platform = colon < 0 ? null : Ids.normalize("platform", target.substring(colon + 1));

        String[] words = fourth.strip().toLowerCase(Locale.ROOT).split("\\s+");
        int next = 0;
        Mode mode = Mode.AT;
        String other = null;
        if (!words[0].isEmpty()) {
            Mode named = modeOf(words[0]);
            if (named != null) {
                mode = named;
                next = 1;
                if (mode == Mode.TO || mode == Mode.BETWEEN) {
                    if (words.length < 2) {
                        throw new IllegalArgumentException("'" + words[0] + "' needs the other station, for example '"
                                + words[0] + " main'");
                    }
                    other = Ids.normalize("station", words[1]);
                    if (other.equals(station)) {
                        throw new IllegalArgumentException("The two stations must differ");
                    }
                    next = 2;
                }
            }
        }
        String filter = next < words.length && !words[next].isEmpty() ? words[next] : null;
        if (next + 1 < words.length) {
            throw new IllegalArgumentException("Line 4 is '[at|approach|after|to <station>|between <station>]"
                    + " [line or line/service]', for example 'approach 5/cw'");
        }
        String lineId = null;
        ServiceKey service = null;
        if (filter != null) {
            if (filter.contains("/")) {
                service = ServiceKey.parse(filter);
            } else {
                lineId = Ids.normalize("line", filter);
            }
        }
        return new Indicator(mode, station, platform, other, lineId, service);
    }

    private static @Nullable Mode modeOf(String word) {
        return switch (word) {
            case "at", "here", "standing" -> Mode.AT;
            case "approach", "approaching", "arriving" -> Mode.APPROACH;
            case "after", "leaving", "left", "from" -> Mode.AFTER;
            case "to", "towards" -> Mode.TO;
            case "between", "with" -> Mode.BETWEEN;
            default -> null;
        };
    }

    /** Whether any of these journeys lights the indicator. */
    public boolean active(Collection<JourneySnapshot> journeys, Network network) {
        for (JourneySnapshot journey : journeys) {
            if (matches(journey, network)) {
                return true;
            }
        }
        return false;
    }

    boolean matches(JourneySnapshot journey, Network network) {
        if (!filterMatches(journey.service())) {
            return false;
        }
        return switch (mode) {
            case AT -> journey.platform() != null && covers(journey.platform());
            case AFTER -> (journey.phase() == JourneyPhase.IN_TRANSIT || journey.phase() == JourneyPhase.COMPLETED)
                    && journey.lastPlatform() != null && covers(journey.lastPlatform());
            case TO -> runsFromTo(journey, network, stationId, platformId, otherStationId);
            case BETWEEN -> runsFromTo(journey, network, stationId, platformId, otherStationId)
                    || runsFromTo(journey, network, otherStationId, null, stationId);
            case APPROACH -> {
                if (journey.service() == null || journey.phase() != JourneyPhase.IN_TRANSIT
                        || journey.approachingIndex() < 0) {
                    yield false;
                }
                Service running = network.service(journey.service()).orElse(null);
                if (running == null || journey.approachingIndex() != journey.nextCallingIndex(running)
                        || journey.approachingIndex() >= running.size()) {
                    yield false;
                }
                Stop stop = running.stop(journey.approachingIndex());
                yield stop.stationId().equals(stationId)
                        && (platformId == null || stop.platformId() == null || platformId.equals(stop.platformId()));
            }
        };
    }

    /** Whether the train has left {@code from} and the next station on its route is {@code to}. */
    private static boolean runsFromTo(JourneySnapshot journey, Network network, String from,
                                      @Nullable String fromPlatform, @Nullable String to) {
        if (to == null || journey.service() == null || journey.phase() != JourneyPhase.IN_TRANSIT) {
            return false;
        }
        PlatformRef last = journey.lastPlatform();
        if (last == null || !last.stationId().equals(from)
                || (fromPlatform != null && !fromPlatform.equals(last.platformId()))) {
            return false;
        }
        Service running = network.service(journey.service()).orElse(null);
        if (running == null) {
            return false;
        }
        int next = running.nextIndex(journey.lastIndex());
        return next >= 0 && next < running.size() && running.stop(next).stationId().equals(to);
    }

    private boolean filterMatches(@Nullable ServiceKey running) {
        if (service != null) {
            return service.equals(running);
        }
        if (lineId != null) {
            return running != null && running.lineId().equals(lineId);
        }
        return true;
    }

    private boolean covers(PlatformRef platform) {
        return platform.stationId().equals(stationId)
                && (platformId == null || platformId.equals(platform.platformId()));
    }

    /** A sentence for the sign builder, for example "lights while a train stands at Main St. platform 2". */
    public String describe(Network network) {
        String where = network.stationName(stationId) + (platformId == null ? "" : " platform " + platformId);
        String other = otherStationId == null ? "" : network.stationName(otherStationId);
        String what = switch (mode) {
            case AT -> "lights while a train stands at " + where;
            case APPROACH -> "lights while a train approaches " + where;
            case AFTER -> "lights while a train runs from " + where + " to its next stop";
            case TO -> "lights while a train runs from " + where + " to " + other;
            case BETWEEN -> "lights while a train runs between " + where + " and " + other;
        };
        String only = service != null ? " (" + service + " only)" : lineId != null ? " (line " + lineId + " only)" : "";
        return what + only;
    }
}
