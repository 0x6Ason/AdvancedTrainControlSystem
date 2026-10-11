package dev.advancedtrains.atcs.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * An immutable snapshot of the whole railway network with lookup indexes.
 *
 * <p>Broken references (for example a stop naming an unknown station) do not prevent building a
 * network; they are reported through {@link #issues()} so an operator can fix them without the
 * plugin refusing to start.
 */
public final class Network {
    public static final Network EMPTY = builder().build();

    private final Map<String, Station> stations;
    private final Map<String, Line> lines;
    private final Map<String, Board> boards;
    private final Map<BlockPos, PlatformRef> triggers;
    private final Map<BlockPos, String> boardSigns;
    private final Map<BlockPos, ServiceKey> spawnSigns;
    private final Map<ServiceKey, Service> services;
    private final Map<String, List<Line>> linesByStation;
    private final List<String> issues;

    private Network(Builder builder) {
        this.stations = Collections.unmodifiableMap(new LinkedHashMap<>(builder.stations));
        this.lines = Collections.unmodifiableMap(new LinkedHashMap<>(builder.lines));
        this.boards = Collections.unmodifiableMap(new LinkedHashMap<>(builder.boards));
        List<String> problems = new ArrayList<>();

        Map<BlockPos, PlatformRef> triggerIndex = new HashMap<>();
        for (Station station : stations.values()) {
            for (Platform platform : station.platforms().values()) {
                for (BlockPos trigger : platform.triggers()) {
                    PlatformRef ref = new PlatformRef(station.id(), platform.id());
                    PlatformRef previous = triggerIndex.putIfAbsent(trigger, ref);
                    if (previous != null) {
                        problems.add("Trigger " + trigger + " is bound to both " + previous + " and " + ref
                                + "; only " + previous + " is used");
                    }
                }
            }
        }
        this.triggers = Collections.unmodifiableMap(triggerIndex);

        Map<ServiceKey, Service> serviceIndex = new LinkedHashMap<>();
        Map<String, Set<Line>> callingLines = new HashMap<>();
        for (Line line : lines.values()) {
            for (Service service : line.services().values()) {
                serviceIndex.put(service.key(), service);
                for (int index = 0; index < service.size(); index++) {
                    Stop stop = service.stop(index);
                    Station station = stations.get(stop.stationId());
                    if (station == null) {
                        problems.add("Service " + service.key() + " stop " + (index + 1)
                                + " names unknown station '" + stop.stationId() + "'");
                        continue;
                    }
                    if (stop.platformId() != null && !station.platforms().containsKey(stop.platformId())) {
                        problems.add("Service " + service.key() + " stop " + (index + 1)
                                + " names unknown platform '" + stop.platformId() + "' of " + station.id());
                    }
                    if (stop.stops()) {
                        callingLines.computeIfAbsent(station.id(), ignored -> new LinkedHashSet<>()).add(line);
                    }
                }
                if (service.size() < 2) {
                    problems.add("Service " + service.key() + " has fewer than two stops");
                }
            }
        }
        Map<BlockPos, ServiceKey> spawnIndex = new HashMap<>();
        for (Service service : serviceIndex.values()) {
            if (service.continuesAs() != null && !serviceIndex.containsKey(service.continuesAs())) {
                problems.add("Service " + service.key() + " continues as unknown service " + service.continuesAs());
            }
            for (BlockPos sign : service.spawnSigns()) {
                ServiceKey previous = spawnIndex.putIfAbsent(sign, service.key());
                if (previous != null && !previous.equals(service.key())) {
                    problems.add("Spawn sign " + sign + " is listed by both " + previous + " and " + service.key()
                            + "; only " + previous + " is used");
                }
            }
        }
        this.spawnSigns = Collections.unmodifiableMap(spawnIndex);
        this.services = Collections.unmodifiableMap(serviceIndex);
        Map<String, List<Line>> byStation = new HashMap<>();
        callingLines.forEach((stationId, set) -> byStation.put(stationId, List.copyOf(set)));
        this.linesByStation = Collections.unmodifiableMap(byStation);

        Map<BlockPos, String> boardIndex = new HashMap<>();
        for (Board board : boards.values()) {
            Station station = stations.get(board.stationId());
            if (station == null) {
                problems.add("Board " + board.id() + " shows unknown station '" + board.stationId() + "'");
            } else {
                for (String platformId : board.platforms()) {
                    if (!station.platforms().containsKey(platformId)) {
                        problems.add("Board " + board.id() + " filters unknown platform '" + platformId + "'");
                    }
                }
            }
            for (BlockPos sign : board.signs()) {
                if (triggerIndex.containsKey(sign)) {
                    problems.add("Board " + board.id() + " sign " + sign
                            + " is also a platform trigger and will not be written");
                    continue;
                }
                String previous = boardIndex.putIfAbsent(sign, board.id());
                if (previous != null) {
                    problems.add("Sign " + sign + " belongs to boards " + previous + " and " + board.id());
                }
            }
        }
        this.boardSigns = Collections.unmodifiableMap(boardIndex);
        this.issues = List.copyOf(problems);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.stations.putAll(stations);
        builder.lines.putAll(lines);
        builder.boards.putAll(boards);
        return builder;
    }

    public Map<String, Station> stations() {
        return stations;
    }

    public Optional<Station> station(String id) {
        return Optional.ofNullable(stations.get(id));
    }

    /** The passenger-facing name of a station, falling back to its id. */
    public String stationName(String id) {
        Station station = stations.get(id);
        return station == null ? id : station.name();
    }

    public Map<String, Line> lines() {
        return lines;
    }

    public Optional<Line> line(String id) {
        return Optional.ofNullable(lines.get(id));
    }

    public Optional<Service> service(ServiceKey key) {
        return Optional.ofNullable(services.get(key));
    }

    public Collection<Service> services() {
        return services.values();
    }

    public Map<String, Board> boards() {
        return boards;
    }

    public Optional<Board> board(String id) {
        return Optional.ofNullable(boards.get(id));
    }

    public Optional<PlatformRef> platformAt(BlockPos position) {
        return Optional.ofNullable(triggers.get(position));
    }

    public Map<BlockPos, PlatformRef> triggers() {
        return triggers;
    }

    /** The service whose trains are spawned by the TrainCarts spawn sign at {@code position}. */
    public Optional<ServiceKey> serviceSpawnedAt(BlockPos position) {
        return Optional.ofNullable(spawnSigns.get(position));
    }

    /** Every spawn sign listed by a service. */
    public Map<BlockPos, ServiceKey> spawnSigns() {
        return spawnSigns;
    }

    /** Whether the sign at {@code position} is part of a departure board. */
    public boolean isBoardSign(BlockPos position) {
        return boardSigns.containsKey(position);
    }

    /** Lines with at least one service that stops at the station. */
    public List<Line> linesCallingAt(String stationId) {
        return linesByStation.getOrDefault(stationId, List.of());
    }

    /** Services that list the station, as a stop or a pass. */
    public List<Service> servicesListing(String stationId) {
        return services.values().stream()
                .filter(service -> service.stops().stream().anyMatch(stop -> stop.stationId().equals(stationId)))
                .toList();
    }

    public List<String> issues() {
        return issues;
    }

    public static final class Builder {
        private final Map<String, Station> stations = new LinkedHashMap<>();
        private final Map<String, Line> lines = new LinkedHashMap<>();
        private final Map<String, Board> boards = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder station(Station station) {
            stations.put(station.id(), station);
            return this;
        }

        public Builder removeStation(String id) {
            stations.remove(id);
            return this;
        }

        public Builder line(Line line) {
            lines.put(line.id(), line);
            return this;
        }

        public Builder removeLine(String id) {
            lines.remove(id);
            return this;
        }

        public Builder board(Board board) {
            boards.put(board.id(), board);
            return this;
        }

        public Builder removeBoard(String id) {
            boards.remove(id);
            return this;
        }

        public Network build() {
            return new Network(this);
        }
    }
}
