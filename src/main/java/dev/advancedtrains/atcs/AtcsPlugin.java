package dev.advancedtrains.atcs;

import com.bergerkiller.bukkit.tc.TrainCarts;
import com.bergerkiller.bukkit.tc.signactions.SignAction;
import dev.advancedtrains.atcs.board.SignLinkPublisher;
import dev.advancedtrains.atcs.command.AtcsCommand;
import dev.advancedtrains.atcs.command.Support;
import dev.advancedtrains.atcs.config.AtcsSettings;
import dev.advancedtrains.atcs.config.Messages;
import dev.advancedtrains.atcs.importer.ImportService;
import dev.advancedtrains.atcs.map.BlueMapLayer;
import dev.advancedtrains.atcs.map.MapLayer;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.NetworkStore;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.operation.JourneyPhase;
import dev.advancedtrains.atcs.runtime.RailwayRuntime;
import dev.advancedtrains.atcs.api.FareApi;
import dev.advancedtrains.atcs.display.DisplayService;
import dev.advancedtrains.atcs.display.Logos;
import dev.advancedtrains.atcs.editor.EditorApi;
import dev.advancedtrains.atcs.editor.EditorServer;
import dev.advancedtrains.atcs.network.NetworkLint;
import dev.advancedtrains.atcs.runtime.TrainHandle;
import dev.advancedtrains.atcs.network.Line;
import java.util.Map;
import dev.advancedtrains.atcs.network.Station;
import dev.advancedtrains.atcs.operation.RailGraph;
import dev.advancedtrains.atcs.selection.SelectionService;
import dev.advancedtrains.atcs.fare.FareStore;
import dev.advancedtrains.atcs.runtime.FareService;
import dev.advancedtrains.atcs.placeholder.AtcsPlaceholders;
import dev.advancedtrains.atcs.traincarts.AtcsSignAction;
import dev.advancedtrains.atcs.traincarts.SignLevers;
import dev.advancedtrains.atcs.web.WebSite;
import dev.advancedtrains.atcs.traincarts.TrainCartsListener;
import dev.advancedtrains.atcs.traincarts.TrainCartsTrains;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;

/**
 * AdvancedTrainControlSystem: passenger information, departure boards and network maps for
 * TrainCarts railways.
 */
public final class AtcsPlugin extends JavaPlugin implements Support.PluginControl {
    private AtcsSettings settings;
    private Messages messages;
    private NetworkStore store;
    private RailwayRuntime runtime;
    private ImportService importer;
    private AtcsSignAction signAction;
    private FareStore fares;
    private FareService fareService;
    private DisplayService displays;
    private SelectionService selections;
    private @Nullable EditorServer editor;
    /** The settings the running editor was started with. */
    private AtcsSettings.@Nullable Editor editorSettings;

    @Override
    public void onEnable() {
        try {
            retireLegacyData();
            saveDefaultConfig();
            if (!Files.exists(dataPath().resolve("messages.yml"))) {
                saveResource("messages.yml", false);
            }
            settings = readSettings();
            messages = readMessages();

            store = new NetworkStore(dataPath().resolve("network.yml"));
            reportNetworkLoad(store.load());

            TrainCartsTrains trains = new TrainCartsTrains();
            runtime = new RailwayRuntime(this, store, trains, trains, new SignLevers(), settings, messages, Clock.systemUTC());
            displays = new DisplayService(this, settings.displays(), new Logos(dataPath().resolve("logos"), getLogger()));
            runtime.setDisplays(displays);
            selections = new SelectionService(this, getServer().getPluginManager().getPlugin("WorldEdit") != null);
            getServer().getPluginManager().registerEvents(selections, this);
            runtime.start(createMap(), createSignLink(), createWeb());
            importer = new ImportService(this, store, runtime.trips()::trips,
                    settings.importer().settings(), settings.importer().chunksPerTick());
            fares = new FareStore(dataPath().resolve("fares.yml"));
            fares.load().forEach(error -> getLogger().warning("fares.yml: " + error));
            fareService = new FareService(this, fares, store, runtime::times, runtime.measure());
            getServer().getServicesManager().register(FareApi.class, fareService, this, ServicePriority.Normal);
            runtime.setFareSummary(fareService::summary);
            if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
                registerPlaceholders(trains);
            }

            startEditor();
            getServer().getPluginManager().registerEvents(new TrainCartsListener(this, store, runtime), this);
            signAction = SignAction.register(new AtcsSignAction(store, runtime));
            // Signs in chunks loaded before this point are matched to the action only now.
            TrainCarts.plugin.getSignController().redetectSignActions();

            PluginCommand command = Objects.requireNonNull(getCommand("atcs"), "atcs command missing from plugin.yml");
            AtcsCommand executor = new AtcsCommand(new Support(store, runtime, trains, importer, this, fareService, displays, selections),
                    getLogger());
            command.setExecutor(executor);
            command.setTabCompleter(executor);

            Network network = store.network();
            getLogger().info("Network: " + network.stations().size() + " stations, " + network.lines().size()
                    + " lines, " + network.services().size() + " services, " + network.boards().size() + " boards, "
                    + network.displays().size() + " displays");
            if (network.stations().isEmpty()) {
                getLogger().info("No stations yet. Start with /atcs import scan, or /atcs station create.");
            }
        } catch (IOException exception) {
            getLogger().log(Level.SEVERE, "Could not read the ATCS data folder; disabling", exception);
            getServer().getPluginManager().disablePlugin(this);
        } catch (LinkageError error) {
            getLogger().log(Level.SEVERE, "This TrainCarts or server version is not supported by ATCS "
                    + getPluginMeta().getVersion() + "; disabling", error);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        stopEditor();
        if (signAction != null) {
            SignAction.unregister(signAction);
            signAction = null;
        }
        if (importer != null) {
            importer.close();
        }
        if (runtime != null) {
            runtime.stop();
        }
    }

    /** Only called when PlaceholderAPI is present, so its classes are never loaded otherwise. */
    private void registerPlaceholders(TrainCartsTrains trains) {
        try {
            new AtcsPlaceholders(runtime, trains, store, () -> messages, fareService, getPluginMeta().getVersion()).register();
            getLogger().info("PlaceholderAPI placeholders registered: %atcs_line%, %atcs_next_time_<station>% and more");
        } catch (RuntimeException | LinkageError exception) {
            getLogger().log(Level.WARNING, "Could not register PlaceholderAPI placeholders", exception);
        }
    }

    // ---------------------------------------------------------------- PluginControl

    @Override
    public List<String> reload() {
        List<String> problems = new ArrayList<>();
        reloadConfig();
        settings = readSettings();
        problems.addAll(settings.warnings());
        messages = readMessages();
        try {
            List<String> errors = store.load();
            errors.forEach(error -> problems.add("network.yml: " + error));
            if (!errors.isEmpty()) {
                problems.add("network.yml is read-only for commands until these are fixed; the last good network stays active");
            }
        } catch (IOException exception) {
            problems.add("Could not read network.yml: " + exception.getMessage());
        }
        store.network().issues().forEach(issue -> problems.add("Network: " + issue));
        try {
            fares.load().forEach(error -> problems.add("fares.yml: " + error));
        } catch (IOException exception) {
            problems.add("Could not read fares.yml: " + exception.getMessage());
        }
        runtime.reconfigure(settings, messages, createMap(), createSignLink(), createWeb());
        runtime.setFareSummary(fareService::summary);
        importer.configure(settings.importer().settings(), settings.importer().chunksPerTick());
        // A reload from the editor itself (saving config.yml) must not cut off its own answer: the
        // server is only restarted when its settings changed, and then a moment later.
        if (editor == null || !settings.editor().equals(editorSettings)) {
            getServer().getScheduler().runTaskLater(this, () -> {
                stopEditor();
                startEditor();
            }, 10L);
        }
        return problems;
    }

    @Override
    public java.util.Optional<String> editorLink() {
        EditorServer running = editor;
        if (running == null || !running.running()) {
            return java.util.Optional.empty();
        }
        String host = settings.editor().host().isBlank() ? running.bind() : settings.editor().host();
        if (host.equals("0.0.0.0") || host.equals("::")) {
            host = getServer().getIp().isBlank() ? "localhost" : getServer().getIp();
        }
        return java.util.Optional.of(running.newLink(host));
    }

    @Override
    public String editorState() {
        if (!settings.editor().enabled()) {
            return "The web editor is off: set integrations.editor.enabled to true in config.yml and /atcs reload";
        }
        return "The web editor could not start on " + settings.editor().bind() + ":" + settings.editor().port()
                + "; see the console (is the port free?)";
    }

    /** Starts the editor's web server when enabled; its operations run on the server thread. */
    private void startEditor() {
        if (!settings.editor().enabled()) {
            return;
        }
        EditorApi.ConfigFiles configs = new EditorApi.ConfigFiles() {
            @Override
            public List<String> names() {
                return List.of("config.yml", "messages.yml");
            }

            @Override
            public Path path(String name) throws EditorApi.ApiException {
                if (!names().contains(name)) {
                    throw new EditorApi.ApiException(404, "Unknown file " + name, null);
                }
                return dataPath().resolve(name);
            }

            @Override
            public List<String> reload() {
                return AtcsPlugin.this.reload();
            }
        };
        EditorApi api = new EditorApi(store, fares,
                network -> NetworkLint.check(network, runtime.lintInputs(fares.table(),
                        (a, b) -> fareService.calculator().planner().route(a, b).isPresent())),
                () -> runtime.liveJson(Clock.systemUTC().instant()),
                runtime::saveLayout, configs, this::editorContext, this::carryLearnedData);
        api.setDispatcher(new EditorApi.Dispatcher() {
            @Override
            public com.google.gson.JsonObject state() {
                return dispatcherState();
            }

            @Override
            public com.google.gson.JsonObject act(com.google.gson.JsonObject order) throws EditorApi.ApiException {
                return dispatch(order);
            }
        });
        EditorServer.Sync sync = new EditorServer.Sync() {
            @Override
            public <T> T call(java.util.concurrent.Callable<T> task) throws Exception {
                if (getServer().isPrimaryThread()) {
                    return task.call();
                }
                return getServer().getScheduler().callSyncMethod(AtcsPlugin.this, task).get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
        };
        EditorServer created = new EditorServer(api, sync, settings.editor().bind(), settings.editor().port(),
                settings.editor().sessionMinutes(), getLogger());
        try {
            created.start();
            editor = created;
            editorSettings = settings.editor();
        } catch (IOException exception) {
            getLogger().log(Level.WARNING, "Could not start the web editor on " + settings.editor().bind() + ":"
                    + settings.editor().port(), exception);
        }
    }

    /** What the dispatcher console shows beside the live trains. */
    private com.google.gson.JsonObject dispatcherState() {
        com.google.gson.JsonObject state = new com.google.gson.JsonObject();
        Network network = store.network();
        com.google.gson.JsonObject holds = new com.google.gson.JsonObject();
        runtime.control().manualHolds().forEach(holds::addProperty);
        state.add("holds", holds);
        com.google.gson.JsonObject skips = new com.google.gson.JsonObject();
        com.google.gson.JsonObject standing = new com.google.gson.JsonObject();
        for (var journey : runtime.journeys()) {
            java.util.Set<String> stations = runtime.skips(journey.trainId());
            if (!stations.isEmpty()) {
                com.google.gson.JsonArray list = new com.google.gson.JsonArray();
                stations.forEach(list::add);
                skips.add(journey.trainId(), list);
            }
            runtime.control().standingAt(journey.trainId()).ifPresent(stand -> standing.addProperty(journey.trainId(), stand.platform().toString()));
        }
        state.add("skips", skips);
        state.add("standing", standing);
        com.google.gson.JsonObject blocks = new com.google.gson.JsonObject();
        runtime.blocks().all().forEach((id, occupancy) -> {
            com.google.gson.JsonObject entry = new com.google.gson.JsonObject();
            entry.addProperty("train", occupancy.trainId());
            entry.addProperty("since", occupancy.since().toEpochMilli());
            blocks.add(id, entry);
        });
        state.add("blocks", blocks);
        com.google.gson.JsonArray switches = new com.google.gson.JsonArray();
        Map<dev.advancedtrains.atcs.network.BlockPos, RailwayRuntime.SwitchOverride> overrides = runtime.switchOverrides();
        runtime.switchSigns().forEach((position, rules) -> {
            com.google.gson.JsonObject entry = new com.google.gson.JsonObject();
            entry.addProperty("at", position.format());
            entry.addProperty("rules", rules.describe());
            RailwayRuntime.SwitchOverride override = overrides.get(position);
            if (override != null) {
                entry.addProperty("set", override.heading().word());
                entry.addProperty("until", override.until().toEpochMilli());
                entry.addProperty("by", override.by());
            }
            switches.add(entry);
        });
        state.add("switches", switches);
        com.google.gson.JsonArray lines = new com.google.gson.JsonArray();
        for (Line line : network.lines().values()) {
            com.google.gson.JsonObject entry = new com.google.gson.JsonObject();
            entry.addProperty("id", line.id());
            entry.addProperty("name", line.name());
            entry.addProperty("short", line.shortName());
            entry.addProperty("color", dev.advancedtrains.atcs.network.Colors.format(line.color()));
            if (line.suspension() != null) {
                entry.addProperty("suspended", line.suspension());
            }
            lines.add(entry);
        }
        state.add("lines", lines);
        com.google.gson.JsonArray events = new com.google.gson.JsonArray();
        for (String line : runtime.recentEvents(60)) {
            try {
                events.add(com.google.gson.JsonParser.parseString(line));
            } catch (RuntimeException ignored) {
                // A line that is not JSON is skipped.
            }
        }
        state.add("events", events);
        state.addProperty("junctionPriority", settings.control().junctionPriority());
        state.addProperty("now", System.currentTimeMillis());
        return state;
    }

    /** Carries out a dispatcher's order from the editor. */
    private com.google.gson.JsonObject dispatch(com.google.gson.JsonObject order) throws EditorApi.ApiException {
        String action = order.has("action") ? order.get("action").getAsString() : "";
        String by = order.has("by") && !order.get("by").isJsonNull() ? order.get("by").getAsString() : "the editor";
        com.google.gson.JsonObject result = new com.google.gson.JsonObject();
        try {
            switch (action) {
                case "hold", "release", "skip", "unskip" -> {
                    String name = order.has("train") ? order.get("train").getAsString() : "";
                    TrainHandle train = runtime.trainSource().train(name).orElse(null);
                    if (train == null) {
                        throw new EditorApi.ApiException(404, "No loaded train named " + name, null);
                    }
                    switch (action) {
                        case "hold" -> runtime.holdByHand(train, order.has("reason") && !order.get("reason").isJsonNull() ? order.get("reason").getAsString() : "");
                        case "release" -> runtime.releaseByHand(train);
                        case "skip" -> {
                            String station = order.get("station").getAsString();
                            if (store.network().station(station).isEmpty()) {
                                throw new EditorApi.ApiException(404, "Unknown station " + station, null);
                            }
                            java.util.Optional<String> problem = runtime.skipStop(train, station);
                            if (problem.isPresent()) {
                                throw new EditorApi.ApiException(409, problem.get(), null);
                            }
                        }
                        default -> runtime.unskipStop(train, order.get("station").getAsString());
                    }
                    result.addProperty("done", action + " " + name);
                }
                case "suspend", "resume" -> {
                    String id = order.has("line") ? order.get("line").getAsString() : "";
                    Line line = store.network().line(id).orElseThrow(() -> new EditorApi.ApiException(404, "Unknown line " + id, null));
                    String notice = action.equals("suspend")
                            ? (order.has("notice") && !order.get("notice").isJsonNull() && !order.get("notice").getAsString().isBlank()
                                    ? order.get("notice").getAsString() : "Please use alternative routes.")
                            : null;
                    store.edit(network -> network.toBuilder().line(line.withSuspension(notice)).build());
                    result.addProperty("done", action + " " + id);
                }
                case "switch" -> {
                    dev.advancedtrains.atcs.network.BlockPos sign = dev.advancedtrains.atcs.network.BlockPos.parse(order.get("sign").getAsString());
                    dev.advancedtrains.atcs.control.Heading heading = dev.advancedtrains.atcs.control.Heading.parse(order.get("heading").getAsString());
                    if (heading == null) {
                        throw new EditorApi.ApiException(400, "Not a direction: " + order.get("heading").getAsString(), null);
                    }
                    int minutes = order.has("minutes") && !order.get("minutes").isJsonNull() ? Math.max(1, Math.min(1440, order.get("minutes").getAsInt())) : 10;
                    runtime.setSwitch(sign, heading, java.time.Duration.ofMinutes(minutes), by);
                    result.addProperty("done", "switch " + sign.coordinates() + " " + heading.word());
                }
                case "clear-switch" -> {
                    runtime.clearSwitch(dev.advancedtrains.atcs.network.BlockPos.parse(order.get("sign").getAsString()));
                    result.addProperty("done", "switch cleared");
                }
                default -> throw new EditorApi.ApiException(400, "Unknown order '" + action + "'", null);
            }
        } catch (dev.advancedtrains.atcs.network.NetworkStore.EditRejectedException exception) {
            throw new EditorApi.ApiException(409, exception.getMessage(), null);
        } catch (IOException exception) {
            throw new EditorApi.ApiException(500, exception.getMessage(), null);
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException | ClassCastException exception) {
            throw new EditorApi.ApiException(400, "Bad order: " + exception.getMessage(), null);
        }
        result.add("state", dispatcherState());
        return result;
    }

    private void stopEditor() {
        EditorServer running = editor;
        editor = null;
        editorSettings = null;
        if (running != null) {
            running.stop();
        }
    }

    /** What the editor page needs beside network.yml: where stations are, their short codes, the saved diagram. */
    private com.google.gson.JsonObject editorContext(Network network) {
        com.google.gson.JsonObject context = new com.google.gson.JsonObject();
        context.addProperty("title", settings.web().title());
        com.google.gson.JsonObject positions = new com.google.gson.JsonObject();
        com.google.gson.JsonObject codes = new com.google.gson.JsonObject();
        com.google.gson.JsonObject observed = new com.google.gson.JsonObject();
        dev.advancedtrains.atcs.map.StationLocator locator = runtime.locator();
        Map<String, java.util.Set<dev.advancedtrains.atcs.network.BlockPos>> seen = runtime.observedTriggers();
        for (Station station : network.stations().values()) {
            locator.station(station).ifPresent(position -> {
                com.google.gson.JsonObject entry = new com.google.gson.JsonObject();
                entry.addProperty("world", position.world());
                entry.addProperty("x", position.x());
                entry.addProperty("y", position.y());
                entry.addProperty("z", position.z());
                positions.add(station.id(), entry);
            });
            codes.addProperty(station.id(), network.code(station.id()));
            java.util.Set<dev.advancedtrains.atcs.network.BlockPos> triggers = seen.get(station.id());
            if (triggers != null && !triggers.isEmpty()) {
                com.google.gson.JsonArray list = new com.google.gson.JsonArray();
                triggers.forEach(position -> list.add(position.format()));
                observed.add(station.id(), list);
            }
        }
        context.add("positions", positions);
        context.add("codes", codes);
        context.add("observed", observed);
        Map<String, Object> layout = runtime.savedLayout();
        if (layout != null) {
            context.add("layout", com.google.gson.JsonParser.parseString(new com.google.gson.Gson().toJson(layout)));
        }
        com.google.gson.JsonArray worlds = new com.google.gson.JsonArray();
        railwayWorlds().forEach(world -> worlds.add(world.getName()));
        context.add("worlds", worlds);
        return context;
    }

    /** Keeps learned times and recorded track for the stops that stay when the editor changes a stop list. */
    private void carryLearnedData(Network before, Network after) {
        for (Service updated : after.services()) {
            before.service(updated.key()).ifPresent(previous -> {
                if (!previous.stops().equals(updated.stops())) {
                    runtime.times().remap(previous, updated);
                    runtime.paths().remap(previous, updated);
                }
            });
        }
    }

    @Override
    public List<String> statusLines() {
        Network network = store.network();
        List<String> lines = new ArrayList<>();
        lines.add("Version " + getPluginMeta().getVersion() + " on " + getServer().getName() + " "
                + getServer().getMinecraftVersion() + ", TrainCarts " + trainCartsVersion());
        int platforms = network.stations().values().stream().mapToInt(station -> station.platforms().size()).sum();
        lines.add("Network: " + network.stations().size() + " stations (" + platforms + " platforms, "
                + network.triggers().size() + " triggers), " + network.lines().size() + " lines, "
                + network.services().size() + " services, " + network.boards().size() + " boards, "
                + network.displays().size() + " displays");
        if (!store.writable()) {
            lines.add("network.yml has " + store.loadErrors().size() + " error(s) and is read-only: " + store.loadErrors().get(0));
        }
        if (!network.issues().isEmpty()) {
            lines.add(network.issues().size() + " network issue(s):");
            network.issues().stream().limit(5).forEach(issue -> lines.add("  " + issue));
        }
        long inService = runtime.journeys().stream()
                .filter(journey -> journey.service() != null && journey.phase() != JourneyPhase.COMPLETED).count();
        lines.add("Trains: " + runtime.journeys().size() + " tracked, " + inService + " in service");
        int learned = 0;
        int segments = 0;
        int recorded = 0;
        for (Service service : network.services()) {
            learned += runtime.times().learnedSegments(service);
            recorded += runtime.paths().recordedSegments(service);
            segments += Math.max(0, service.circular() ? service.size() : service.size() - 1);
        }
        lines.add("Learned travel times: " + learned + "/" + segments + " segments; recorded track paths: "
                + recorded + "/" + segments);
        RailGraph graph = runtime.railGraph();
        if (!graph.edges().isEmpty()) {
            lines.add("Rail graph: " + graph.edges().size() + " stretches of track, " + graph.junctions().size()
                    + " junctions, " + Math.round(graph.totalLength()) + " blocks in all");
        }
        long fleets = network.services().stream().filter(service -> service.fleet() != null).count();
        lines.add("Fleets on " + fleets + " service(s); spawn chunks held: " + runtime.heldChunks()
                + "; every train keeps chunks loaded: " + onOff(settings.trains().keepChunksLoaded())
                + "; train naming: " + onOff(settings.trains().rename()));
        lines.add("Indicator signs: " + runtime.indicators().size() + " loaded, " + runtime.indicators().lit() + " lit");
        if (runtime.blocks().size() > 0 || !runtime.control().manualHolds().isEmpty()) {
            lines.add("Blocks occupied: " + runtime.blocks().size() + "; trains held by hand: " + runtime.control().manualHolds().size());
        }
        AtcsSettings.Passenger passenger = settings.passenger();
        lines.add("Passenger information: " + (passenger.enabled() ? "on" : "off")
                + (passenger.enabled() ? " (boss bar " + onOff(passenger.bossBar()) + ", titles " + onOff(passenger.arrivalTitle())
                + ", chat " + onOff(passenger.chatAnnouncements()) + ")" : ""));
        lines.add("BlueMap: " + integration("BlueMap", settings.blueMap().enabled(), runtime.map() != null && runtime.map().ready())
                + "; SignLink: " + integration("SignLink", settings.signLink().enabled(), runtime.signLinkActive()));
        lines.add("Website: " + (!runtime.webConfigured() ? "off"
                : runtime.webFolder().map(folder -> "written to " + folder).orElse("waiting for a folder (BlueMap not loaded yet?)")));
        lines.add("Debug logging: " + onOff(runtime.debug()));
        return lines;
    }

    @Override
    public void setDebug(boolean debug) {
        runtime.setDebug(debug);
    }

    @Override
    public boolean debug() {
        return runtime.debug();
    }

    @Override
    public List<World> railwayWorlds() {
        return getServer().getWorlds().stream().filter(world -> !TrainCarts.isWorldDisabled(world)).toList();
    }

    // ---------------------------------------------------------------- setup helpers

    private Path dataPath() {
        return getDataFolder().toPath();
    }

    private AtcsSettings readSettings() {
        AtcsSettings read = AtcsSettings.read(getConfig());
        int version = getConfig().getInt("config-version", 0);
        if (version < AtcsSettings.CONFIG_VERSION) {
            getLogger().warning("config.yml is from an older version; missing options use their defaults");
        }
        read.warnings().forEach(warning -> getLogger().warning("config.yml " + warning));
        return read;
    }

    private Messages readMessages() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(dataPath().resolve("messages.yml").toFile());
        try (InputStream bundled = getResource("messages.yml")) {
            if (bundled != null) {
                config.setDefaults(YamlConfiguration.loadConfiguration(
                        new InputStreamReader(bundled, StandardCharsets.UTF_8)));
                config.options().copyDefaults(true);
            }
        } catch (IOException exception) {
            getLogger().log(Level.WARNING, "Could not read the bundled messages.yml", exception);
        }
        return Messages.read(config, getLogger());
    }

    private void reportNetworkLoad(List<String> errors) {
        if (!errors.isEmpty()) {
            getLogger().severe("network.yml has " + errors.size() + " error(s). The valid parts are used, and the file"
                    + " is read-only for commands until it is fixed and reloaded:");
            errors.forEach(error -> getLogger().severe("  " + error));
        }
        store.network().issues().forEach(issue -> getLogger().warning("Network: " + issue));
    }

    private @Nullable MapLayer createMap() {
        if (!settings.blueMap().enabled() || getServer().getPluginManager().getPlugin("BlueMap") == null) {
            return null;
        }
        try {
            String website = settings.web().enabled() && settings.web().folder().isBlank() ? "atcs/" : null;
            return new BlueMapLayer(this, settings.blueMap(), new Logos(dataPath().resolve("logos"), getLogger()), website, getLogger());
        } catch (LinkageError error) {
            getLogger().log(Level.WARNING, "This BlueMap version is not supported; map output disabled", error);
            return null;
        }
    }

    private @Nullable WebSite createWeb() {
        if (!settings.web().enabled()) {
            return null;
        }
        try {
            return new WebSite(this, settings.web(), getLogger());
        } catch (LinkageError error) {
            getLogger().log(Level.WARNING, "This BlueMap version is not supported; website disabled", error);
            return null;
        }
    }

    private @Nullable SignLinkPublisher createSignLink() {
        if (!settings.signLink().enabled() || getServer().getPluginManager().getPlugin("SignLink") == null) {
            return null;
        }
        try {
            return new SignLinkPublisher();
        } catch (LinkageError error) {
            getLogger().log(Level.WARNING, "This SignLink version is not supported; SignLink variables disabled", error);
            return null;
        }
    }

    private String trainCartsVersion() {
        var plugin = getServer().getPluginManager().getPlugin("Train_Carts");
        return plugin == null ? "missing" : plugin.getPluginMeta().getVersion();
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }

    private String integration(String plugin, boolean enabled, boolean active) {
        if (!enabled) {
            return "disabled in config";
        }
        if (getServer().getPluginManager().getPlugin(plugin) == null) {
            return "not installed";
        }
        return active ? "active" : "installed, waiting for it to finish loading";
    }

    /**
     * The 0.1 prototype stored a SQLite database, a web token and a different config.yml in this
     * folder. They are moved aside once so the new defaults can be written; nothing is deleted.
     */
    private void retireLegacyData() throws IOException {
        Path folder = dataPath();
        Path config = folder.resolve("config.yml");
        boolean legacyConfig = Files.exists(config)
                && YamlConfiguration.loadConfiguration(config.toFile()).contains("operating-mode");
        List<String> legacyFiles = new ArrayList<>(List.of("railcontrol.db", "railcontrol.db-shm",
                "railcontrol.db-wal", "web-admin.token", "announcements.yml"));
        if (legacyConfig) {
            legacyFiles.add("config.yml");
        }
        Path archive = folder.resolve("legacy-0.1");
        List<String> moved = new ArrayList<>();
        for (String name : legacyFiles) {
            Path file = folder.resolve(name);
            if (Files.exists(file)) {
                Files.createDirectories(archive);
                Files.move(file, archive.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                moved.add(name);
            }
        }
        if (!moved.isEmpty()) {
            getLogger().info("Moved files from the 0.1 prototype into " + archive + ": " + String.join(", ", moved)
                    + ". They are no longer used and can be deleted.");
        }
    }
}
