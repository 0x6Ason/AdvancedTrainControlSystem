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
        return problems;
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
        long fleets = network.services().stream().filter(service -> service.fleet() != null).count();
        lines.add("Fleets on " + fleets + " service(s); spawn chunks held: " + runtime.heldChunks()
                + "; every train keeps chunks loaded: " + onOff(settings.trains().keepChunksLoaded())
                + "; train naming: " + onOff(settings.trains().rename()));
        lines.add("Indicator signs: " + runtime.indicators().size() + " loaded, " + runtime.indicators().lit() + " lit");
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
