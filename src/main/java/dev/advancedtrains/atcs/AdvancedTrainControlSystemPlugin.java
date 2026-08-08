package dev.advancedtrains.atcs;

import com.bergerkiller.bukkit.tc.TrainCarts;
import dev.advancedtrains.atcs.announcement.AnnouncementCatalog;
import dev.advancedtrains.atcs.command.AtcsCommand;
import dev.advancedtrains.atcs.config.PluginConfiguration;
import dev.advancedtrains.atcs.event.AsyncOperationalEventPublisher;
import dev.advancedtrains.atcs.event.JdbcOperationalEventStore;
import dev.advancedtrains.atcs.event.OperationalEvent;
import dev.advancedtrains.atcs.event.OperationalEventType;
import dev.advancedtrains.atcs.discovery.InfrastructureCandidateRepository;
import dev.advancedtrains.atcs.discovery.InfrastructureDiscoveryService;
import dev.advancedtrains.atcs.discovery.InfrastructureDiscoverySummary;
import dev.advancedtrains.atcs.discovery.RailCandidateRepository;
import dev.advancedtrains.atcs.discovery.RailDiscoveryService;
import dev.advancedtrains.atcs.discovery.RailDiscoverySummary;
import dev.advancedtrains.atcs.integration.DependencyProbe;
import dev.advancedtrains.atcs.integration.DependencyStatus;
import dev.advancedtrains.atcs.integration.traincarts.PassengerHudServiceFactory;
import dev.advancedtrains.atcs.integration.traincarts.ReceiverRegistrarFactory;
import dev.advancedtrains.atcs.integration.traincarts.TrainCartsWorldPolicy;
import dev.advancedtrains.atcs.hud.NoOpPassengerHudService;
import dev.advancedtrains.atcs.hud.PassengerHudService;
import dev.advancedtrains.atcs.hud.TrainHudStateRegistry;
import dev.advancedtrains.atcs.legacy.LegacyInventorySummary;
import dev.advancedtrains.atcs.legacy.LegacyReviewRepository;
import dev.advancedtrains.atcs.legacy.LegacyReviewSummary;
import dev.advancedtrains.atcs.legacy.LegacySignInventoryRepository;
import dev.advancedtrains.atcs.legacy.LegacySignScanService;
import dev.advancedtrains.atcs.persistence.SQLiteDatabase;
import dev.advancedtrains.atcs.receiver.NoOpReceiverRegistrar;
import dev.advancedtrains.atcs.receiver.ReceiverEventService;
import dev.advancedtrains.atcs.receiver.ReceiverRegistrar;
import dev.advancedtrains.atcs.receiver.ReceiverStateTracker;
import dev.advancedtrains.atcs.runtime.SystemRuntimeState;
import dev.advancedtrains.atcs.web.AdminDataRepository;
import dev.advancedtrains.atcs.web.EmbeddedWebServer;
import dev.advancedtrains.atcs.world.RailwayWorldPolicy;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public final class AdvancedTrainControlSystemPlugin extends JavaPlugin {
    private final Clock clock = Clock.systemUTC();
    private AsyncOperationalEventPublisher eventPublisher;
    private ReceiverRegistrar receiverRegistrar = new NoOpReceiverRegistrar("plugin is not initialized");
    private PassengerHudService passengerHudService = new NoOpPassengerHudService("plugin is not initialized");
    private LegacySignScanService signScanService;
    private InfrastructureDiscoveryService infrastructureDiscoveryService;
    private RailDiscoveryService railDiscoveryService;
    private EmbeddedWebServer webServer;
    private UUID startupId;

    @Override
    public void onEnable() {
        startupId = UUID.randomUUID();
        try {
            saveDefaultConfig();
            ensureBundledResource("announcements.yml");
            PluginConfiguration configuration = PluginConfiguration.load(this);

            SQLiteDatabase database = new SQLiteDatabase(configuration.database(), getClass(), clock);
            SQLiteDatabase.InitializationResult initialization = database.initialize();
            if (initialization.backupPath() != null) {
                getLogger().info("Created pre-migration database backup: " + initialization.backupPath());
            }

            DependencyProbe dependencyProbe = new DependencyProbe(getServer().getPluginManager());
            DependencyStatus trainCarts = dependencyProbe.inspect("Train_Carts");
            DependencyStatus bkCommonLib = dependencyProbe.inspect("BKCommonLib");
            DependencyStatus signLink = dependencyProbe.inspect("SignLink");
            org.bukkit.plugin.Plugin trainCartsPlugin =
                    getServer().getPluginManager().getPlugin("Train_Carts");
            if (!(trainCartsPlugin instanceof TrainCarts trainCartsApi) || !trainCartsApi.isEnabled()) {
                throw new IllegalStateException("TrainCarts is required and must be enabled before ATCS");
            }
            if (!"2.0.0".equals(trainCartsApi.getPluginMeta().getVersion())) {
                throw new IllegalStateException("ATCS requires TrainCarts 2.0.0 exactly; found "
                        + trainCartsApi.getPluginMeta().getVersion());
            }
            RailwayWorldPolicy worldPolicy = new TrainCartsWorldPolicy(this, trainCartsApi, getLogger());

            eventPublisher = new AsyncOperationalEventPublisher(
                    new JdbcOperationalEventStore(database, clock), getLogger());
            ReceiverEventService receiverEvents = new ReceiverEventService(
                    new ReceiverStateTracker(), eventPublisher, clock, getLogger());
            TrainHudStateRegistry hudStateRegistry = new TrainHudStateRegistry();
            receiverEvents.subscribeTransitions(hudStateRegistry::observe);
            receiverRegistrar = ReceiverRegistrarFactory.create(
                    configuration.receiverSignsEnabled(),
                    trainCarts,
                    bkCommonLib,
                    receiverEvents,
                    getLogger());
            passengerHudService = PassengerHudServiceFactory.create(
                    this,
                    configuration.passengerHud(),
                    trainCarts,
                    bkCommonLib,
                    hudStateRegistry,
                    clock,
                    getLogger());

            LegacySignInventoryRepository legacyRepository = new LegacySignInventoryRepository(database);
            LegacyReviewRepository reviewRepository = new LegacyReviewRepository(
                    database,
                    configuration.legacyReview(),
                    worldPolicy,
                    clock);
            LegacyReviewSummary reviewSummary = reviewRepository.refresh();
            LegacyInventorySummary inventorySummary = legacyRepository.loadSummary();
            signScanService = new LegacySignScanService(
                    this,
                    legacyRepository,
                    reviewRepository,
                    clock,
                    getLogger(),
                    configuration.inventoryScan().chunksPerTick(),
                    worldPolicy,
                    inventorySummary);
            InfrastructureCandidateRepository infrastructureRepository =
                    new InfrastructureCandidateRepository(
                            database,
                            worldPolicy,
                            configuration.infrastructureDiscovery(),
                            clock);
            InfrastructureDiscoverySummary infrastructureSummary =
                    infrastructureRepository.loadLatestSummary();
            infrastructureDiscoveryService = new InfrastructureDiscoveryService(
                    this,
                    infrastructureRepository,
                    infrastructureSummary,
                    getLogger());
            RailCandidateRepository railRepository = new RailCandidateRepository(database);
            RailDiscoverySummary railSummary = railRepository.loadLatestSummary();
            railDiscoveryService = new RailDiscoveryService(
                    this,
                    railRepository,
                    configuration.infrastructureDiscovery().rail(),
                    worldPolicy,
                    railSummary,
                    clock,
                    getLogger());
            String webStatus = "disabled by configuration";
            if (configuration.web().enabled()) {
                webServer = new EmbeddedWebServer(
                        configuration.web(),
                        new AdminDataRepository(database, worldPolicy, clock),
                        clock,
                        getLogger());
                webServer.start();
                webStatus = webServer.statusMessage();
                getLogger().info("Web administration token file: "
                        + configuration.web().tokenFile());
                if (!java.net.InetAddress.getByName(configuration.web().bindAddress()).isLoopbackAddress()) {
                    getLogger().warning(
                            "Web administration is bound beyond loopback. Use an authenticated TLS reverse proxy.");
                }
            }

            SystemRuntimeState runtimeState = new SystemRuntimeState(
                    configuration.operatingMode(),
                    database.path(),
                    database.schemaVersion(),
                    database.isHealthy(),
                    trainCarts,
                    bkCommonLib,
                    signLink,
                    receiverRegistrar,
                    Instant.now(clock),
                    database.countOperationalEvents());
            eventPublisher.subscribe(runtimeState::recordEvent);

            AnnouncementCatalog announcementCatalog = AnnouncementCatalog.load(
                    new File(getDataFolder(), "announcements.yml"));
            registerCommand(new AtcsCommand(
                    this,
                    runtimeState,
                    receiverEvents,
                    announcementCatalog,
                    signScanService,
                    passengerHudService,
                    reviewRepository,
                    infrastructureDiscoveryService,
                    railDiscoveryService,
                    webStatus,
                    worldPolicy));
            publishLifecycleEvent(OperationalEventType.SYSTEM_STARTED);

            getLogger().info("AdvancedTrainControlSystem is enabled on Minecraft "
                    + getServer().getMinecraftVersion() + ".");
            getLogger().info("Operating mode: " + configuration.operatingMode()
                    + "; control writes: " + (runtimeState.controlWritesEnabled() ? "enabled" : "disabled"));
            getLogger().info("Receiver adapter: " + receiverRegistrar.statusMessage());
            getLogger().info("Passenger HUD: " + passengerHudService.statusMessage());
            getLogger().info("TrainCarts active loaded worlds: " + worldPolicy.activeDescription());
            getLogger().info("Legacy review queue: " + reviewSummary.issueSigns()
                    + " signs with " + reviewSummary.openIssues() + " open issues");
            getLogger().info("Infrastructure candidate snapshots: "
                    + infrastructureSummary.runId().map(ignored -> "available").orElse("none"));
            getLogger().info("Rail candidate snapshots: "
                    + railSummary.runId().map(ignored -> "available").orElse("none"));
            getLogger().info("Web administration: " + webStatus);
            if (configuration.operatingMode() == dev.advancedtrains.atcs.runtime.OperatingMode.ACTIVE
                    && !runtimeState.controlWritesEnabled()) {
                getLogger().warning("ACTIVE was requested, but dependency or database health forced control writes off.");
            }
        } catch (Exception | LinkageError exception) {
            getLogger().log(Level.SEVERE, "AdvancedTrainControlSystem failed to initialize safely", exception);
            receiverRegistrar.close();
            passengerHudService.close();
            if (signScanService != null) {
                signScanService.close();
                signScanService = null;
            }
            if (infrastructureDiscoveryService != null) {
                infrastructureDiscoveryService.close();
                infrastructureDiscoveryService = null;
            }
            if (railDiscoveryService != null) {
                railDiscoveryService.close();
                railDiscoveryService = null;
            }
            if (webServer != null) {
                webServer.close();
                webServer = null;
            }
            if (eventPublisher != null) {
                eventPublisher.close(Duration.ofSeconds(2));
                eventPublisher = null;
            }
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (webServer != null) {
            webServer.close();
            webServer = null;
        }
        if (railDiscoveryService != null) {
            railDiscoveryService.close();
            railDiscoveryService = null;
        }
        if (infrastructureDiscoveryService != null) {
            infrastructureDiscoveryService.close();
            infrastructureDiscoveryService = null;
        }
        if (signScanService != null) {
            signScanService.close();
            signScanService = null;
        }
        passengerHudService.close();
        receiverRegistrar.close();
        if (eventPublisher != null) {
            CompletableFuture<?> stopping = publishLifecycleEvent(OperationalEventType.SYSTEM_STOPPING);
            try {
                stopping.join();
            } catch (RuntimeException exception) {
                getLogger().log(Level.SEVERE, "Could not persist the shutdown event", exception);
            }
            if (!eventPublisher.close(Duration.ofSeconds(5))) {
                getLogger().warning("Operational event worker did not stop cleanly before timeout.");
            }
        }
    }

    private CompletableFuture<?> publishLifecycleEvent(OperationalEventType type) {
        if (eventPublisher == null) {
            return CompletableFuture.completedFuture(null);
        }
        String key = "server-session:" + startupId + ":" + type.name();
        OperationalEvent event = OperationalEvent.create(
                key,
                type,
                Instant.now(clock),
                Map.of(
                        "plugin_version", getPluginMeta().getVersion(),
                        "server_version", getServer().getMinecraftVersion()));
        return eventPublisher.publish(event);
    }

    private void registerCommand(AtcsCommand commandHandler) {
        PluginCommand command = getCommand("atcs");
        if (command == null) {
            throw new IllegalStateException("The atcs command is missing from plugin.yml");
        }
        command.setExecutor(commandHandler);
        command.setTabCompleter(commandHandler);
    }

    private void ensureBundledResource(String resourceName) {
        File resource = new File(getDataFolder(), resourceName);
        if (!resource.isFile()) {
            saveResource(resourceName, false);
        }
    }
}
