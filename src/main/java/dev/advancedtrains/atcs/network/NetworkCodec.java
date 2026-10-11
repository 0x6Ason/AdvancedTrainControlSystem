package dev.advancedtrains.atcs.network;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads and writes {@code network.yml}.
 *
 * <p>Reading never throws for a bad entry. Each problem is recorded with its YAML path and the
 * entry is skipped; callers must then refuse to write the network back, otherwise the skipped
 * entries would be lost from the file.
 */
public final class NetworkCodec {
    public static final int FORMAT_VERSION = 1;

    private static final List<String> HEADER = List.of(
            "AdvancedTrainControlSystem network definition.",
            "",
            "Edit with /atcs commands in game, or edit this file and run /atcs reload.",
            "Stops are written as station, station:platform, or ~station for a station a",
            "service runs through without stopping. Triggers and board signs are world,x,y,z.",
            "Full reference: see the plugin README.");

    public record ReadResult(Network network, List<String> errors) {
        public ReadResult {
            errors = List.copyOf(errors);
        }

        public boolean clean() {
            return errors.isEmpty();
        }
    }

    public ReadResult read(String yaml) {
        YamlConfiguration root = new YamlConfiguration();
        try {
            root.loadFromString(yaml);
        } catch (InvalidConfigurationException exception) {
            return new ReadResult(Network.EMPTY, List.of("Invalid YAML: " + exception.getMessage()));
        }
        return read(root);
    }

    public ReadResult read(ConfigurationSection root) {
        List<String> errors = new ArrayList<>();
        int version = root.getInt("version", FORMAT_VERSION);
        if (version > FORMAT_VERSION) {
            errors.add("version " + version + " is newer than this plugin supports (" + FORMAT_VERSION + ")");
            return new ReadResult(Network.EMPTY, errors);
        }
        Network.Builder builder = Network.builder();
        for (Station station : readStations(root.getConfigurationSection("stations"), "stations", errors)) {
            builder.station(station);
        }
        for (Line line : readLines(root.getConfigurationSection("lines"), errors)) {
            builder.line(line);
        }
        ConfigurationSection boards = root.getConfigurationSection("boards");
        if (boards != null) {
            Set<String> seen = new HashSet<>();
            for (String boardKey : boards.getKeys(false)) {
                Board board = readBoard(boards, boardKey, errors);
                if (board != null && !seen.add(board.id())) {
                    errors.add("boards." + boardKey + ": duplicate board id '" + board.id() + "' (ids are case-insensitive)");
                } else if (board != null) {
                    builder.board(board);
                }
            }
        }
        return new ReadResult(builder.build(), errors);
    }

    /** Reads a {@code stations:} section. Also used for import proposals. */
    public List<Station> readStations(ConfigurationSection section, String path, List<String> errors) {
        List<Station> stations = new ArrayList<>();
        if (section == null) {
            return stations;
        }
        Set<String> seen = new HashSet<>();
        for (String stationKey : section.getKeys(false)) {
            String stationPath = path + "." + stationKey;
            ConfigurationSection node = section.getConfigurationSection(stationKey);
            if (node == null) {
                errors.add(stationPath + ": expected a section");
                continue;
            }
            try {
                Map<String, Platform> platforms = new LinkedHashMap<>();
                ConfigurationSection platformNodes = node.getConfigurationSection("platforms");
                if (platformNodes != null) {
                    for (String platformKey : platformNodes.getKeys(false)) {
                        Platform platform = readPlatform(platformNodes, platformKey,
                                stationPath + ".platforms." + platformKey, errors);
                        if (platform != null && platforms.put(platform.id(), platform) != null) {
                            errors.add(stationPath + ".platforms." + platformKey + ": duplicate platform id '"
                                    + platform.id() + "' (ids are case-insensitive)");
                        }
                    }
                }
                String location = node.getString("location");
                Station station = new Station(
                        stationKey,
                        node.getString("name", ""),
                        platforms,
                        location == null || location.isBlank() ? null : BlockPos.parse(location));
                if (!seen.add(station.id())) {
                    throw new IllegalArgumentException("duplicate station id '" + station.id() + "' (ids are case-insensitive)");
                }
                stations.add(station);
            } catch (IllegalArgumentException exception) {
                errors.add(stationPath + ": " + exception.getMessage());
            }
        }
        return stations;
    }

    /** Reads a {@code lines:} section. Also used for import proposals. */
    public List<Line> readLines(ConfigurationSection section, List<String> errors) {
        List<Line> lines = new ArrayList<>();
        if (section == null) {
            return lines;
        }
        Set<String> seen = new HashSet<>();
        for (String lineKey : section.getKeys(false)) {
            Line line = readLine(section, lineKey, errors);
            if (line != null && !seen.add(line.id())) {
                errors.add("lines." + lineKey + ": duplicate line id '" + line.id() + "' (ids are case-insensitive)");
            } else if (line != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    private Platform readPlatform(ConfigurationSection parent, String key, String path, List<String> errors) {
        ConfigurationSection node = parent.getConfigurationSection(key);
        try {
            if (node == null) {
                // Allows the compact form  "1": [world,x,y,z, ...]
                return new Platform(key, "", positions(parent, key, path, errors));
            }
            return new Platform(
                    key,
                    node.getString("name", ""),
                    positions(node, "triggers", path + ".triggers", errors));
        } catch (IllegalArgumentException exception) {
            errors.add(path + ": " + exception.getMessage());
            return null;
        }
    }

    private Line readLine(ConfigurationSection lines, String key, List<String> errors) {
        String path = "lines." + key;
        ConfigurationSection node = lines.getConfigurationSection(key);
        if (node == null) {
            errors.add(path + ": expected a section");
            return null;
        }
        try {
            String lineId = Ids.normalize("line", key);
            Map<String, Service> services = new LinkedHashMap<>();
            ConfigurationSection serviceNodes = node.getConfigurationSection("services");
            if (serviceNodes != null) {
                for (String serviceKey : serviceNodes.getKeys(false)) {
                    Service service = readService(lineId, serviceNodes, serviceKey,
                            path + ".services." + serviceKey, errors);
                    if (service != null && services.put(service.key().serviceId(), service) != null) {
                        errors.add(path + ".services." + serviceKey + ": duplicate service id '"
                                + service.key().serviceId() + "' (ids are case-insensitive)");
                    }
                }
            }
            String suspension = node.getString("suspended");
            return new Line(
                    lineId,
                    node.getString("name", ""),
                    node.getString("short-name", ""),
                    Colors.parse(node.getString("color", "#FFFFFF")),
                    services,
                    suspension == null || suspension.isBlank() ? null : suspension);
        } catch (IllegalArgumentException exception) {
            errors.add(path + ": " + exception.getMessage());
            return null;
        }
    }

    private Service readService(
            String lineId,
            ConfigurationSection services,
            String key,
            String path,
            List<String> errors) {
        ConfigurationSection node = services.getConfigurationSection(key);
        if (node == null) {
            errors.add(path + ": expected a section");
            return null;
        }
        try {
            ServiceKey serviceKey = new ServiceKey(lineId, key);
            if (node.isSet("stops") && !node.isList("stops")) {
                throw new IllegalArgumentException("stops must be a list, for example [main:1, nerdy, ~golden-beach]");
            }
            List<Stop> stops = new ArrayList<>();
            List<?> rawStops = node.getList("stops", List.of());
            for (int index = 0; index < rawStops.size(); index++) {
                Object raw = rawStops.get(index);
                String stopPath = path + ".stops[" + (index + 1) + "]";
                try {
                    stops.add(readStop(raw));
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("stop " + (index + 1) + ": " + exception.getMessage(), exception);
                } catch (ClassCastException exception) {
                    throw new IllegalArgumentException(stopPath + " has an unsupported format", exception);
                }
            }
            String continuesAs = node.getString("continues-as");
            ServiceKey continuation = null;
            if (continuesAs != null && !continuesAs.isBlank()) {
                continuation = continuesAs.contains("/")
                        ? ServiceKey.parse(continuesAs)
                        : new ServiceKey(lineId, continuesAs);
            }
            ConfigurationSection match = node.getConfigurationSection("match");
            ServiceMatch serviceMatch = match == null ? ServiceMatch.NONE : new ServiceMatch(
                    stringOrList(match, "destinations", "destination"),
                    stringOrList(match, "tags", "tag"),
                    stringOrList(match, "train-names", "train-name"));
            Fleet fleet = null;
            ConfigurationSection fleetNode = node.getConfigurationSection("fleet");
            if (fleetNode != null) {
                try {
                    fleet = new Fleet(
                            fleetNode.getInt("trains", 1),
                            Fleet.parseEvery(fleetNode.getString("every", "01:00")),
                            BlockPos.parse(fleetNode.getString("spawn", "")),
                            Fleet.Direction.parse(fleetNode.getString("direction", "")),
                            fleetNode.getString("train", "1m"));
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("fleet: " + exception.getMessage(), exception);
                }
            }
            return new Service(
                    serviceKey,
                    node.getString("name", key),
                    stops,
                    node.getBoolean("circular", false),
                    continuation,
                    serviceMatch,
                    positions(node, "spawn-signs", path + ".spawn-signs", errors),
                    fleet);
        } catch (IllegalArgumentException exception) {
            errors.add(path + ": " + exception.getMessage());
            return null;
        }
    }

    private static Stop readStop(Object raw) {
        if (raw instanceof String text) {
            return Stop.parse(text);
        }
        if (raw instanceof Map<?, ?> map) {
            Object station = map.get("station");
            if (station == null) {
                throw new IllegalArgumentException("missing 'station'");
            }
            Object platform = map.get("platform");
            Object pass = map.get("pass");
            return new Stop(
                    station.toString(),
                    platform == null ? null : platform.toString(),
                    pass != null && Boolean.parseBoolean(pass.toString()));
        }
        if (raw instanceof Number number) {
            return Stop.of(number.toString());
        }
        throw new IllegalArgumentException("unsupported stop '" + raw + "'");
    }

    private Board readBoard(ConfigurationSection boards, String key, List<String> errors) {
        String path = "boards." + key;
        ConfigurationSection node = boards.getConfigurationSection(key);
        if (node == null) {
            errors.add(path + ": expected a section");
            return null;
        }
        try {
            return new Board(
                    key,
                    node.getString("station", ""),
                    stringOrList(node, "platforms", "platform"),
                    positions(node, "signs", path + ".signs", errors));
        } catch (IllegalArgumentException exception) {
            errors.add(path + ": " + exception.getMessage());
            return null;
        }
    }

    /** Reads a list of positions, reporting a value that is not a list instead of silently reading nothing. */
    private static List<BlockPos> positions(ConfigurationSection node, String key, String path, List<String> errors) {
        if (node.isSet(key) && !node.isList(key)) {
            errors.add(path + ": expected a list of world,x,y,z positions");
            return List.of();
        }
        return parsePositions(node.getStringList(key), path, errors);
    }

    private static List<BlockPos> parsePositions(List<String> values, String path, List<String> errors) {
        List<BlockPos> positions = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index);
            // An unquoted flow list such as [world,1,2,3] arrives as four separate items.
            if (!value.contains(",") && !isInteger(value) && index + 3 < values.size()
                    && isInteger(values.get(index + 1)) && isInteger(values.get(index + 2)) && isInteger(values.get(index + 3))) {
                value = String.join(",", values.subList(index, index + 4));
                index += 3;
            }
            try {
                positions.add(BlockPos.parse(value));
            } catch (IllegalArgumentException exception) {
                errors.add(path + ": " + exception.getMessage());
            }
        }
        return positions;
    }

    private static boolean isInteger(String value) {
        return value != null && value.strip().matches("-?\\d+");
    }

    private static List<String> stringOrList(ConfigurationSection node, String listKey, String singleKey) {
        if (node.isList(listKey)) {
            return node.getList(listKey, List.of()).stream().map(String::valueOf).toList();
        }
        String single = node.getString(listKey, node.getString(singleKey));
        return single == null ? List.of() : List.of(single);
    }

    public String write(Network network) {
        YamlConfiguration root = new YamlConfiguration();
        root.options().setHeader(HEADER);
        root.set("version", FORMAT_VERSION);
        ConfigurationSection stations = root.createSection("stations");
        writeStations(stations, network.stations().values());

        writeLines(root.createSection("lines"), network.lines().values());

        ConfigurationSection boards = root.createSection("boards");
        for (Board board : network.boards().values()) {
            ConfigurationSection node = boards.createSection(board.id());
            node.set("station", board.stationId());
            if (!board.platforms().isEmpty()) {
                node.set("platforms", board.platforms());
            }
            node.set("signs", board.signs().stream().map(BlockPos::format).toList());
        }
        return root.saveToString();
    }

    /** Writes lines into {@code section}. Also used for import proposals. */
    public void writeLines(ConfigurationSection section, Iterable<Line> lines) {
        for (Line line : lines) {
            ConfigurationSection node = section.createSection(line.id());
            node.set("name", line.name());
            if (!line.shortName().equals(line.id().toUpperCase(java.util.Locale.ROOT))) {
                node.set("short-name", line.shortName());
            }
            node.set("color", Colors.format(line.color()));
            if (line.suspension() != null) {
                node.set("suspended", line.suspension());
            }
            ConfigurationSection services = node.createSection("services");
            for (Service service : line.services().values()) {
                ConfigurationSection serviceNode = services.createSection(service.key().serviceId());
                serviceNode.set("name", service.name());
                serviceNode.set("stops", service.stops().stream().map(Stop::format).toList());
                if (service.circular()) {
                    serviceNode.set("circular", true);
                }
                if (service.continuesAs() != null) {
                    ServiceKey next = service.continuesAs();
                    serviceNode.set("continues-as",
                            next.lineId().equals(line.id()) ? next.serviceId() : next.toString());
                }
                if (!service.spawnSigns().isEmpty()) {
                    serviceNode.set("spawn-signs", service.spawnSigns().stream().map(BlockPos::format).toList());
                }
                if (service.fleet() != null) {
                    Fleet fleet = service.fleet();
                    ConfigurationSection fleetNode = serviceNode.createSection("fleet");
                    fleetNode.set("trains", fleet.trains());
                    fleetNode.set("every", Fleet.formatEvery(fleet.every()));
                    fleetNode.set("spawn", fleet.spawn().format());
                    fleetNode.set("direction", fleet.direction().name().toLowerCase(java.util.Locale.ROOT));
                    fleetNode.set("train", fleet.train());
                }
                ServiceMatch match = service.match();
                if (!match.isEmpty()) {
                    ConfigurationSection matchNode = serviceNode.createSection("match");
                    if (!match.destinations().isEmpty()) {
                        matchNode.set("destinations", match.destinations());
                    }
                    if (!match.tags().isEmpty()) {
                        matchNode.set("tags", match.tags());
                    }
                    if (!match.trainNames().isEmpty()) {
                        matchNode.set("train-names", match.trainNames());
                    }
                }
            }
        }
    }

    /** Writes stations into {@code section}. Also used for import proposals. */
    public void writeStations(ConfigurationSection section, Iterable<Station> stations) {
        for (Station station : stations) {
            ConfigurationSection node = section.createSection(station.id());
            node.set("name", station.name());
            if (station.location() != null) {
                node.set("location", station.location().format());
            }
            ConfigurationSection platforms = node.createSection("platforms");
            for (Platform platform : station.platforms().values()) {
                ConfigurationSection platformNode = platforms.createSection(platform.id());
                if (!platform.name().isEmpty()) {
                    platformNode.set("name", platform.name());
                }
                platformNode.set("triggers", platform.triggers().stream().map(BlockPos::format).toList());
            }
        }
    }
}
