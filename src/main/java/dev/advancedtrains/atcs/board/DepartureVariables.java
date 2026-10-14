package dev.advancedtrains.atcs.board;

import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Platform;
import dev.advancedtrains.atcs.network.Station;
import dev.advancedtrains.atcs.operation.Departures;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleFunction;
import java.util.function.Function;

/**
 * The departure variables shown on signs through SignLink, named short enough to type on a sign
 * line. For the station with code {@code gb} and its platform {@code 3}:
 *
 * <pre>
 * gb.1      first departure from any platform, all in one line: "A Local New Village St. 0:05"
 * gb.1d     its destination            gb.1t   its time: 0:05, 45s or Boarding
 * gb.1l     its line badge             gb.1s   its service
 * gb.1p     its platform
 * gb.p3.1   first departure from platform 3, and gb.p3.1d, gb.p3.1t ... as above
 * </pre>
 *
 * On a sign they are written between percent signs: {@code %gb.p3.1t%}.
 */
public final class DepartureVariables {
    public static final String SUMMARY = "";
    public static final String LINE = "l";
    public static final String SERVICE = "s";
    public static final String DESTINATION = "d";
    public static final String TIME = "t";
    public static final String PLATFORM = "p";
    private static final String[] FIELDS = {SUMMARY, LINE, SERVICE, DESTINATION, TIME, PLATFORM};

    private DepartureVariables() {
    }

    /** The variable for the {@code rank}-th departure of a station, or of one platform when given. */
    public static String name(Network network, String stationId, String platformId, int rank, String field) {
        String code = network.code(stationId);
        return (platformId == null ? code : code + ".p" + platformId) + "." + rank + field;
    }

    /**
     * Every variable with its current value.
     *
     * @param departures the departures of a station from all its platforms, soonest first
     * @param duration   formats seconds the way boards do
     */
    public static Map<String, String> values(Network network, int perList,
                                             Function<String, List<Departures.Departure>> departures,
                                             DoubleFunction<String> duration) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Station station : network.stations().values()) {
            if (network.servicesListing(station.id()).isEmpty()) {
                continue;
            }
            List<Departures.Departure> upcoming = departures.apply(station.id());
            fill(values, network, station.id(), null, upcoming, perList, duration);
            for (Platform platform : station.platforms().values()) {
                List<Departures.Departure> fromPlatform = upcoming.stream()
                        .filter(departure -> platform.id().equals(departure.platformId())).toList();
                fill(values, network, station.id(), platform.id(), fromPlatform, perList, duration);
            }
        }
        return values;
    }

    private static void fill(Map<String, String> values, Network network, String stationId, String platformId,
                             List<Departures.Departure> upcoming, int perList, DoubleFunction<String> duration) {
        for (int rank = 1; rank <= perList; rank++) {
            Departures.Departure departure = rank <= upcoming.size() ? upcoming.get(rank - 1) : null;
            if (departure == null) {
                for (String field : FIELDS) {
                    values.put(name(network, stationId, platformId, rank, field), "");
                }
                continue;
            }
            Line line = network.line(departure.service().key().lineId()).orElse(null);
            String badge = line == null ? "" : line.shortName();
            String destination = departure.service().destinationFrom(departure.stopIndex())
                    .map(stop -> network.stationName(stop.stationId()))
                    .orElse(departure.service().name());
            String time = departure.status() == Departures.Status.BOARDING ? "Boarding"
                    : departure.etaSeconds().isPresent() ? duration.apply(departure.etaSeconds().getAsDouble()) : "--";
            String platform = departure.platformId() == null ? "" : departure.platformId();
            values.put(name(network, stationId, platformId, rank, SUMMARY),
                    badge + " " + departure.service().name() + " " + destination + " " + time);
            values.put(name(network, stationId, platformId, rank, LINE), badge);
            values.put(name(network, stationId, platformId, rank, SERVICE), departure.service().name());
            values.put(name(network, stationId, platformId, rank, DESTINATION), destination);
            values.put(name(network, stationId, platformId, rank, TIME), time);
            values.put(name(network, stationId, platformId, rank, PLATFORM), platform);
        }
    }
}
