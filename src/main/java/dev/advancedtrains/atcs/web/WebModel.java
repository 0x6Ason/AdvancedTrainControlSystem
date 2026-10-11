package dev.advancedtrains.atcs.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.advancedtrains.atcs.map.StationLocator;
import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Colors;
import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.Station;
import dev.advancedtrains.atcs.network.Stop;
import dev.advancedtrains.atcs.operation.Departures;
import dev.advancedtrains.atcs.operation.JourneyPhase;
import dev.advancedtrains.atcs.operation.JourneySnapshot;
import dev.advancedtrains.atcs.operation.TrainView;
import dev.advancedtrains.atcs.operation.TravelTimes;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Function;

/** What the website shows, as the JSON files its page reads: the network once, the trains often. */
public final class WebModel {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private WebModel() {
    }

    /** Stations, lines and services with everything learned about them, for the map and the planner. */
    public static String network(String title, int refreshSeconds, Network network, StationLocator locator,
                                 TravelTimes times, double defaultDwellSeconds, Instant now) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("title", title);
        root.put("refreshSeconds", refreshSeconds);
        root.put("generated", now.toEpochMilli());
        root.put("defaultDwell", defaultDwellSeconds);
        List<Object> stations = new ArrayList<>();
        for (Station station : network.stations().values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", station.id());
            entry.put("name", station.name());
            Optional<BlockPos> position = locator.station(station);
            entry.put("x", position.map(BlockPos::x).orElse(null));
            entry.put("z", position.map(BlockPos::z).orElse(null));
            entry.put("platforms", List.copyOf(station.platforms().keySet()));
            entry.put("lines", network.linesCallingAt(station.id()).stream().map(Line::id).toList());
            stations.add(entry);
        }
        root.put("stations", stations);
        List<Object> lines = new ArrayList<>();
        for (Line line : network.lines().values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", line.id());
            entry.put("name", line.name());
            entry.put("short", line.shortName());
            entry.put("color", Colors.format(line.color()));
            entry.put("suspended", line.suspended());
            List<Object> services = new ArrayList<>();
            for (Service service : line.services().values()) {
                services.add(service(network, service, times));
            }
            entry.put("services", services);
            lines.add(entry);
        }
        root.put("lines", lines);
        return GSON.toJson(root);
    }

    private static Map<String, Object> service(Network network, Service service, TravelTimes times) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", service.key().serviceId());
        entry.put("key", service.key().toString());
        entry.put("name", service.name());
        entry.put("circular", service.circular());
        entry.put("destination", service.destination().map(stop -> network.stationName(stop.stationId())).orElse(null));
        entry.put("continues", service.continuesAs() == null ? null : service.continuesAs().toString());
        List<Object> stops = new ArrayList<>();
        List<Object> segments = new ArrayList<>();
        List<Object> dwells = new ArrayList<>();
        for (int index = 0; index < service.size(); index++) {
            Stop stop = service.stop(index);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("station", stop.stationId());
            item.put("platform", stop.platformId());
            item.put("pass", stop.pass());
            stops.add(item);
            OptionalDouble segment = service.nextIndex(index) >= 0 ? times.segmentSeconds(service, index) : OptionalDouble.empty();
            segments.add(segment.isPresent() ? round(segment.getAsDouble()) : null);
            OptionalDouble dwell = times.dwellSeconds(service, index);
            dwells.add(dwell.isPresent() ? round(dwell.getAsDouble()) : null);
        }
        entry.put("stops", stops);
        entry.put("segments", segments);
        entry.put("dwells", dwells);
        return entry;
    }

    /**
     * Every tracked train and the next departures of every station.
     *
     * @param view       the train's view when it runs a service
     * @param departures the station's upcoming departures, soonest first
     */
    public static String live(Network network, Collection<JourneySnapshot> journeys,
                              Function<JourneySnapshot, Optional<TrainView>> view,
                              Function<String, List<Departures.Departure>> departures,
                              TravelTimes times, int departuresPerStation, Instant now) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generated", now.toEpochMilli());
        List<Object> trains = new ArrayList<>();
        for (JourneySnapshot journey : journeys) {
            TrainView running = view.apply(journey).orElse(null);
            if (running == null || running.completed()) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", journey.trainId());
            entry.put("line", running.line().id());
            entry.put("service", running.service().key().serviceId());
            entry.put("key", running.service().key().toString());
            entry.put("destination", running.destinationName(network));
            entry.put("phase", journey.phase().name().toLowerCase(Locale.ROOT));
            entry.put("last", journey.lastIndex());
            entry.put("next", running.nextIndex());
            entry.put("fraction", fraction(journey, running.service(), times, now));
            entry.put("eta", running.etaNext().isPresent() ? round(running.etaNext().getAsDouble()) : null);
            entry.put("at", journey.currentStationId());
            trains.add(entry);
        }
        root.put("trains", trains);
        Map<String, Object> boards = new LinkedHashMap<>();
        for (Station station : network.stations().values()) {
            List<Object> upcoming = new ArrayList<>();
            for (Departures.Departure departure : departures.apply(station.id())) {
                if (upcoming.size() >= departuresPerStation) {
                    break;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("train", departure.trainId());
                entry.put("key", departure.service().key().toString());
                entry.put("line", departure.service().key().lineId());
                entry.put("service", departure.service().name());
                entry.put("destination", departure.service().destinationFrom(departure.stopIndex())
                        .map(stop -> network.stationName(stop.stationId())).orElse(departure.service().name()));
                entry.put("platform", departure.platformId());
                entry.put("eta", departure.etaSeconds().isPresent() ? round(departure.etaSeconds().getAsDouble()) : null);
                entry.put("boarding", departure.status() == Departures.Status.BOARDING);
                upcoming.add(entry);
            }
            if (!upcoming.isEmpty()) {
                boards.put(station.id(), upcoming);
            }
        }
        root.put("departures", boards);
        return GSON.toJson(root);
    }

    /** How far along its current stretch of track a running train is, 0 to 1; 0 at a stop. */
    static double fraction(JourneySnapshot journey, Service service, TravelTimes times, Instant now) {
        if (journey.phase() != JourneyPhase.IN_TRANSIT || journey.segmentStart() == null) {
            return 0;
        }
        OptionalDouble segment = times.segmentSeconds(service, journey.lastIndex());
        if (segment.isEmpty() || segment.getAsDouble() <= 0) {
            return 0.5;
        }
        double elapsed = Duration.between(journey.segmentStart(), now).toMillis() / 1000.0;
        return round(Math.max(0, Math.min(0.97, elapsed / segment.getAsDouble())));
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
