package dev.advancedtrains.atcs.network;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
            "Transfers are walks between nearby stations: between: [a, b], walk in seconds or",
            "m:ss (left out: estimated from the distance), outside: false for a passage that",
            "stays inside the stations.",
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
        ConfigurationSection displays = root.getConfigurationSection("displays");
        if (displays != null) {
            Set<String> seenDisplays = new HashSet<>();
            for (String displayKey : displays.getKeys(false)) {
                Display display = readDisplay(displays, displayKey, errors);
                if (display != null && !seenDisplays.add(display.id())) {
                    errors.add("displays." + displayKey + ": duplicate display id '" + display.id() + "' (ids are case-insensitive)");
                } else if (display != null) {
                    builder.display(display);
                }
            }
        }
        builder.map(readMap(root.getConfigurationSection("map"), errors));
        for (Output output : readOutputs(root, errors)) {
            builder.output(output);
        }
        Set<String> walks = new HashSet<>();
        for (Transfer transfer : readTransfers(root, errors)) {
            if (!walks.add(transfer.key())) {
                errors.add("transfers: " + transfer.a() + " and " + transfer.b() + " are connected twice; the first is used");
            } else {
                builder.transfer(transfer);
            }
        }
        return new ReadResult(builder.build(), errors);
    }

    private static MapOverrides readMap(@Nullable ConfigurationSection section, List<String> errors) {
        if (section == null) {
            return MapOverrides.EMPTY;
        }
        Map<String, MapOverrides.StationOverride> stations = new LinkedHashMap<>();
        ConfigurationSection stationNodes = section.getConfigurationSection("stations");
        if (stationNodes != null) {
            for (String key : stationNodes.getKeys(false)) {
                ConfigurationSection node = stationNodes.getConfigurationSection(key);
                String path = "map.stations." + key;
                if (node == null) {
                    errors.add(path + ": expected a section with column, row, label or hidden");
                    continue;
                }
                try {
                    Integer column = node.isInt("column") ? node.getInt("column") : null;
                    Integer row = node.isInt("row") ? node.getInt("row") : null;
                    stations.put(key, new MapOverrides.StationOverride(column, row, node.getString("label"), node.getBoolean("hidden", false)));
                } catch (IllegalArgumentException exception) {
                    errors.add(path + ": " + exception.getMessage());
                }
            }
        }
        Map<String, Integer> colors = new LinkedHashMap<>();
        ConfigurationSection lineNodes = section.getConfigurationSection("lines");
        if (lineNodes != null) {
            for (String key : lineNodes.getKeys(false)) {
                try {
                    colors.put(key, Colors.parse(String.valueOf(lineNodes.get(key))));
                } catch (IllegalArgumentException exception) {
                    errors.add("map.lines." + key + ": " + exception.getMessage());
                }
            }
        }
        try {
            return new MapOverrides(stations, colors);
        } catch (IllegalArgumentException exception) {
            errors.add("map: " + exception.getMessage());
            return MapOverrides.EMPTY;
        }
    }

    private static @Nullable Display readDisplay(ConfigurationSection section, String key, List<String> errors) {
        String path = "displays." + key;
        ConfigurationSection node = section.getConfigurationSection(key);
        if (node == null) {
            errors.add(path + ": expected a section");
            return null;
        }
        try {
            Display.Kind kind = Display.Kind.parse(node.getString("kind", ""));
            String serviceText = node.getString("service");
            ServiceKey service = serviceText == null || serviceText.isBlank() ? null : ServiceKey.parse(serviceText);
            List<Display.Frame> frames = new ArrayList<>();
            for (Map<?, ?> item : node.getMapList("frames")) {
                Object at = item.get("at");
                Object map = item.get("map");
                if (at == null || !(map instanceof Number)) {
                    throw new IllegalArgumentException("each frame needs 'at: world,x,y,z' and 'map: id'");
                }
                frames.add(new Display.Frame(BlockPos.parse(String.valueOf(at)),
                        item.get("column") instanceof Number column ? column.intValue() : 0,
                        item.get("row") instanceof Number row ? row.intValue() : 0,
                        ((Number) map).intValue()));
            }
            int columns = node.getInt("columns", frames.stream().mapToInt(Display.Frame::column).max().orElse(-1) + 1);
            int rows = node.getInt("rows", frames.stream().mapToInt(Display.Frame::row).max().orElse(-1) + 1);
            Display.Hologram hologram = null;
            String at = node.getString("at");
            if (at != null && !at.isBlank()) {
                String entity = node.getString("entity");
                UUID uuid = null;
                if (entity != null && !entity.isBlank()) {
                    try {
                        uuid = UUID.fromString(entity);
                    } catch (IllegalArgumentException exception) {
                        throw new IllegalArgumentException("entity '" + entity + "' is not a UUID");
                    }
                }
                hologram = Display.Hologram.parse(at, uuid);
                if (node.isSet("facing")) {
                    if (!node.isDouble("facing") && !node.isInt("facing")) {
                        throw new IllegalArgumentException("facing must be a number of degrees");
                    }
                    hologram = hologram.withFacing((float) node.getDouble("facing"));
                }
            }
            return new Display(key, kind, node.getString("station"), stringOrList(node, "platforms", "platform"), service,
                    columns, rows, frames, node.getIntegerList("maps"), hologram, stringOrList(node, "options", "option"),
                    node.getString("note"));
        } catch (IllegalArgumentException exception) {
            errors.add(path + ": " + exception.getMessage());
            return null;
        }
    }

    private static List<Output> readOutputs(ConfigurationSection root, List<String> errors) {
        List<Output> outputs = new ArrayList<>();
        if (!root.isSet("outputs")) {
            return outputs;
        }
        if (!root.isList("outputs")) {
            errors.add("outputs: expected a list of { block: world,x,y,z, target: main:2, mode: approach }");
            return outputs;
        }
        List<?> items = root.getList("outputs", List.of());
        for (int index = 0; index < items.size(); index++) {
            String path = "outputs[" + (index + 1) + "]";
            if (!(items.get(index) instanceof Map<?, ?> item) || item.get("block") == null || item.get("target") == null) {
                errors.add(path + ": expected { block: world,x,y,z, target: main:2, mode: approach }");
                continue;
            }
            try {
                outputs.add(Output.of(BlockPos.parse(String.valueOf(item.get("block"))), String.valueOf(item.get("target")),
                        item.get("mode") == null ? "" : String.valueOf(item.get("mode"))));
            } catch (IllegalArgumentException exception) {
                errors.add(path + ": " + exception.getMessage());
            }
        }
        return outputs;
    }

    private static List<Transfer> readTransfers(ConfigurationSection root, List<String> errors) {
        List<Transfer> transfers = new ArrayList<>();
        if (!root.isSet("transfers")) {
            return transfers;
        }
        if (!root.isList("transfers")) {
            errors.add("transfers: expected a list of { between: [station, station], walk: seconds, outside: true|false }");
            return transfers;
        }
        List<?> items = root.getList("transfers", List.of());
        for (int index = 0; index < items.size(); index++) {
            String path = "transfers[" + (index + 1) + "]";
            if (!(items.get(index) instanceof Map<?, ?> item)) {
                errors.add(path + ": expected { between: [station, station], walk: seconds }");
                continue;
            }
            if (!(item.get("between") instanceof List<?> between) || between.size() != 2) {
                errors.add(path + ": 'between' must list exactly two stations");
                continue;
            }
            Integer walk = null;
            Object rawWalk = item.get("walk");
            if (rawWalk != null) {
                try {
                    walk = (int) Fleet.parseEvery(String.valueOf(rawWalk)).getSeconds();
                } catch (IllegalArgumentException exception) {
                    errors.add(path + ": walk " + exception.getMessage());
                    continue;
                }
            }
            Object rawOutside = item.get("outside");
            if (rawOutside != null && !(rawOutside instanceof Boolean)) {
                errors.add(path + ": outside must be true or false");
                continue;
            }
            try {
                transfers.add(new Transfer(String.valueOf(between.get(0)), String.valueOf(between.get(1)), walk,
                        rawOutside == null || (Boolean) rawOutside));
            } catch (IllegalArgumentException exception) {
                errors.add(path + ": " + exception.getMessage());
            }
        }
        return transfers;
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
                String footprint = node.getString("footprint");
                Station station = new Station(
                        stationKey,
                        node.getString("name", ""),
                        platforms,
                        location == null || location.isBlank() ? null : BlockPos.parse(location),
                        node.getString("code"),
                        footprint == null || footprint.isBlank() ? null : Box.parse(footprint));
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
                    Fleet.Hours hours = fleetNode.isString("hours") ? Fleet.Hours.parse(fleetNode.getString("hours", "")) : null;
                    Fleet.OffPeak offPeak = null;
                    ConfigurationSection offPeakNode = fleetNode.getConfigurationSection("off-peak");
                    if (offPeakNode != null) {
                        offPeak = new Fleet.OffPeak(offPeakNode.getInt("trains", 0), Fleet.Hours.parse(offPeakNode.getString("hours", "")));
                    }
                    fleet = new Fleet(
                            fleetNode.getInt("trains", 1),
                            Fleet.parseEvery(fleetNode.getString("every", "01:00")),
                            BlockPos.parse(fleetNode.getString("spawn", "")),
                            Fleet.Direction.parse(fleetNode.getString("direction", "")),
                            fleetNode.getString("train", "1m"),
                            hours,
                            offPeak);
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("fleet: " + exception.getMessage(), exception);
                }
            }
            Double speed = null;
            if (node.isSet("speed")) {
                if (!node.isDouble("speed") && !node.isInt("speed")) {
                    throw new IllegalArgumentException("speed must be a number of blocks per tick, for example 0.6");
                }
                speed = node.getDouble("speed");
            }
            List<Integer> timetable = new ArrayList<>();
            if (node.isSet("timetable")) {
                if (!node.isList("timetable")) {
                    throw new IllegalArgumentException("timetable must be a list of minutes past the hour, for example [':00', ':30']");
                }
                for (Object raw : node.getList("timetable", List.of())) {
                    String text = String.valueOf(raw).strip();
                    if (text.startsWith(":")) {
                        text = text.substring(1);
                    }
                    try {
                        timetable.add(Integer.parseInt(text));
                    } catch (NumberFormatException exception) {
                        throw new IllegalArgumentException("timetable: '" + raw + "' is not a minute past the hour (write :00, :15, :30)");
                    }
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
                    fleet,
                    speed,
                    timetable,
                    node.getString("short"));
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
            Map<BlockPos, Integer> mirrors = new LinkedHashMap<>();
            ConfigurationSection mirrorNodes = node.getConfigurationSection("mirrors");
            if (node.isList("mirrors")) {
                for (Map<?, ?> entry : node.getMapList("mirrors")) {
                    Object sign = entry.get("sign");
                    Object line = entry.get("line");
                    if (sign == null || !(line instanceof Number number)) {
                        errors.add(path + ".mirrors: expected { sign: world,x,y,z, line: 1 }, not " + entry);
                        continue;
                    }
                    mirrors.put(BlockPos.parse(sign.toString()), number.intValue());
                }
            } else if (mirrorNodes != null) {
                // Written by 0.2.0 snapshots as "world,x,y,z": line. A world name with a dot in it
                // came back as nested sections, so the full key path is the position.
                for (String position : mirrorNodes.getKeys(true)) {
                    if (mirrorNodes.isConfigurationSection(position)) {
                        continue;
                    }
                    if (!mirrorNodes.isInt(position)) {
                        errors.add(path + ".mirrors: '" + position + "' needs the line it repeats, for example 1");
                        continue;
                    }
                    mirrors.put(BlockPos.parse(position), mirrorNodes.getInt(position));
                }
            } else if (node.isSet("mirrors")) {
                errors.add(path + ".mirrors: expected a list of { sign: world,x,y,z, line: 1 }");
            }
            return new Board(
                    key,
                    node.getString("station", ""),
                    stringOrList(node, "platforms", "platform"),
                    positions(node, "signs", path + ".signs", errors),
                    mirrors);
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
            if (!board.mirrors().isEmpty()) {
                // A list rather than positions as keys: a world name may contain a dot.
                List<Map<String, Object>> mirrorNodes = new ArrayList<>();
                board.mirrors().forEach((sign, line) -> {
                    Map<String, Object> mirror = new LinkedHashMap<>();
                    mirror.put("sign", sign.format());
                    mirror.put("line", line);
                    mirrorNodes.add(mirror);
                });
                node.set("mirrors", mirrorNodes);
            }
        }

        if (!network.displays().isEmpty()) {
            ConfigurationSection displays = root.createSection("displays");
            for (Display display : network.displays().values()) {
                ConfigurationSection node = displays.createSection(display.id());
                node.set("kind", display.kind().key());
                if (display.stationId() != null) {
                    node.set("station", display.stationId());
                }
                if (!display.platforms().isEmpty()) {
                    node.set("platforms", display.platforms());
                }
                if (display.service() != null) {
                    node.set("service", display.service().toString());
                }
                if (display.columns() > 0 || display.rows() > 0) {
                    node.set("columns", display.columns());
                    node.set("rows", display.rows());
                }
                if (!display.frames().isEmpty()) {
                    List<Map<String, Object>> frames = new ArrayList<>();
                    for (Display.Frame frame : display.frames()) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("at", frame.at().format());
                        item.put("column", frame.column());
                        item.put("row", frame.row());
                        item.put("map", frame.map());
                        frames.add(item);
                    }
                    node.set("frames", frames);
                }
                if (!display.maps().isEmpty()) {
                    node.set("maps", display.maps());
                }
                if (display.hologram() != null) {
                    node.set("at", display.hologram().formatPosition());
                    if (display.hologram().entity() != null) {
                        node.set("entity", display.hologram().entity().toString());
                    }
                    if (display.hologram().facing() != null) {
                        node.set("facing", (double) Math.round(display.hologram().facing() * 10) / 10);
                    }
                }
                if (!display.options().isEmpty()) {
                    node.set("options", display.options());
                }
                if (display.note() != null) {
                    node.set("note", display.note());
                }
            }
        }

        if (!network.map().isEmpty()) {
            ConfigurationSection map = root.createSection("map");
            if (!network.map().stations().isEmpty()) {
                ConfigurationSection adjusted = map.createSection("stations");
                network.map().stations().forEach((id, override) -> {
                    ConfigurationSection node = adjusted.createSection(id);
                    if (override.column() != null) {
                        node.set("column", override.column());
                        node.set("row", override.row());
                    }
                    if (override.label() != null) {
                        node.set("label", override.label());
                    }
                    if (override.hidden()) {
                        node.set("hidden", true);
                    }
                });
            }
            if (!network.map().lineColors().isEmpty()) {
                ConfigurationSection lines = map.createSection("lines");
                network.map().lineColors().forEach((id, color) -> lines.set(id, Colors.format(color)));
            }
        }

        if (!network.outputs().isEmpty()) {
            List<Map<String, Object>> outputs = new ArrayList<>();
            for (Output output : network.outputs().values()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("block", output.block().format());
                item.put("target", output.target());
                if (!output.mode().isEmpty()) {
                    item.put("mode", output.mode());
                }
                outputs.add(item);
            }
            root.set("outputs", outputs);
        }
        if (!network.transfers().isEmpty()) {
            List<Map<String, Object>> transfers = new ArrayList<>();
            for (Transfer transfer : network.transfers()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("between", List.of(transfer.a(), transfer.b()));
                if (transfer.walk() != null) {
                    item.put("walk", transfer.walk());
                }
                item.put("outside", transfer.outside());
                transfers.add(item);
            }
            root.set("transfers", transfers);
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
                if (service.shortName() != null) {
                    serviceNode.set("short", service.shortName());
                }
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
                if (service.speed() != null) {
                    serviceNode.set("speed", service.speed());
                }
                if (!service.timetable().isEmpty()) {
                    serviceNode.set("timetable", service.timetable().stream()
                            .map(minute -> ":" + (minute < 10 ? "0" : "") + minute).toList());
                }
                if (service.fleet() != null) {
                    Fleet fleet = service.fleet();
                    ConfigurationSection fleetNode = serviceNode.createSection("fleet");
                    fleetNode.set("trains", fleet.trains());
                    fleetNode.set("every", Fleet.formatEvery(fleet.every()));
                    fleetNode.set("spawn", fleet.spawn().format());
                    fleetNode.set("direction", fleet.direction().name().toLowerCase(java.util.Locale.ROOT));
                    fleetNode.set("train", fleet.train());
                    if (fleet.hours() != null) {
                        fleetNode.set("hours", fleet.hours().format());
                    }
                    if (fleet.offPeak() != null) {
                        fleetNode.set("off-peak.trains", fleet.offPeak().trains());
                        fleetNode.set("off-peak.hours", fleet.offPeak().hours().format());
                    }
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
            if (station.code() != null) {
                node.set("code", station.code());
            }
            if (station.location() != null) {
                node.set("location", station.location().format());
            }
            if (station.footprint() != null) {
                node.set("footprint", station.footprint().format());
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
