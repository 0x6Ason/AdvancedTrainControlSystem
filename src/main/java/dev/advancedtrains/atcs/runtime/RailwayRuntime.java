package dev.advancedtrains.atcs.runtime;

import dev.advancedtrains.atcs.board.BoardService;
import dev.advancedtrains.atcs.board.SignLinkPublisher;
import dev.advancedtrains.atcs.config.AtcsSettings;
import dev.advancedtrains.atcs.config.Messages;
import dev.advancedtrains.atcs.control.Heading;
import dev.advancedtrains.atcs.control.StationControl;
import dev.advancedtrains.atcs.control.StopOptions;
import dev.advancedtrains.atcs.control.SwitchRules;
import dev.advancedtrains.atcs.map.MapLayer;
import dev.advancedtrains.atcs.map.StationLocator;
import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Board;
import dev.advancedtrains.atcs.network.Fleet;
import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.NetworkStore;
import dev.advancedtrains.atcs.network.Platform;
import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.network.Station;
import dev.advancedtrains.atcs.network.Stop;
import dev.advancedtrains.atcs.operation.Departures;
import dev.advancedtrains.atcs.operation.ConsistentEstimates;
import dev.advancedtrains.atcs.operation.Estimator;
import dev.advancedtrains.atcs.operation.EtaCalculator;
import dev.advancedtrains.atcs.operation.EtaContext;
import dev.advancedtrains.atcs.operation.EtaSamples;
import dev.advancedtrains.atcs.operation.JourneyEvent;
import dev.advancedtrains.atcs.operation.JourneyPhase;
import dev.advancedtrains.atcs.operation.JourneySnapshot;
import dev.advancedtrains.atcs.operation.JourneyTracker;
import dev.advancedtrains.atcs.operation.RouteMatcher;
import dev.advancedtrains.atcs.operation.RouteMeasure;
import dev.advancedtrains.atcs.operation.ServiceResolver;
import dev.advancedtrains.atcs.operation.StopObservation;
import dev.advancedtrains.atcs.operation.TrackPaths;
import dev.advancedtrains.atcs.operation.TrainInfo;
import dev.advancedtrains.atcs.operation.TrainView;
import dev.advancedtrains.atcs.operation.TravelTimes;
import dev.advancedtrains.atcs.operation.TripLog;
import dev.advancedtrains.atcs.passenger.PassengerDisplay;
import dev.advancedtrains.atcs.util.Files2;
import dev.advancedtrains.atcs.web.WebModel;
import dev.advancedtrains.atcs.web.WebSite;
import org.bukkit.Location;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Runs the railway: turns platform signals into journeys, keeps passenger displays, boards and maps
 * up to date, and persists learned timings. Main thread only.
 */
public final class RailwayRuntime {
    private static final Duration JOURNEY_EXPIRY = Duration.ofMinutes(30);

    private final Plugin plugin;
    private final Logger logger;
    private final NetworkStore store;
    private final TrainSource trains;
    private final @Nullable TrainSpawner spawner;
    private static final int WEB_DEPARTURES = 4;
    private final ChunkKeeper chunkKeeper;
    private final IndicatorService indicators;
    private final FleetManager fleets = new FleetManager();
    private final Map<ServiceKey, String> lastSpawnProblem = new HashMap<>();
    private final Clock clock;
    private final Path timingsFile;
    private final Path pathsFile;
    private final Path tripsFile;
    private final Path samplesFile;
    private final EtaSamples samples = new EtaSamples();
    private final EventLog eventLog;
    private int tick;
    private final JourneyTracker tracker = new JourneyTracker();
    private final StationControl control = new StationControl();
    /** The hold reason last logged per train, so debug output shows changes only. */
    private final Map<String, String> holdReasons = new HashMap<>();
    /** Where each loaded train is and how fast it moves, refreshed every second. */
    private record TrainState(String world, double x, double y, double z, double speed, Instant at, @Nullable Instant standingSince) {
    }

    private static final double STANDING_SPEED = 0.02;
    private static final Duration STANDING_AFTER = Duration.ofSeconds(2);
    private static final Duration STATE_FRESH = Duration.ofSeconds(5);
    private final Map<String, TrainState> trainStates = new HashMap<>();
    /** What the estimator may know beyond learned times; see {@link EtaContext}. */
    private final EtaContext context = new EtaContext() {
        @Override
        public OptionalDouble segmentProgress(String trainId, Service service, int fromIndex) {
            TrainState state = trainStates.get(trainId);
            if (state == null || Duration.between(state.at(), clock.instant()).compareTo(STATE_FRESH) > 0) {
                return OptionalDouble.empty();
            }
            return paths.progress(service, fromIndex, state.world(), state.x(), state.y(), state.z());
        }

        @Override
        public boolean standing(String trainId) {
            TrainState state = trainStates.get(trainId);
            return state != null && state.standingSince() != null
                    && Duration.between(state.standingSince(), clock.instant()).compareTo(STANDING_AFTER) >= 0;
        }

        @Override
        public OptionalDouble remainingStop(String trainId, Instant now) {
            return control.remainingStop(trainId, tracker.snapshots(), now, other -> untilNextStation(other, now));
        }

        @Override
        public @Nullable String holdReason(String trainId) {
            String reason = holdReasons.get(trainId);
            return reason == null || reason.isEmpty() ? null : reason;
        }
    };
    private @Nullable ConsistentEstimates estimates;
    private @Nullable Instant estimatesAt;

    /** Seconds until a running train reaches the next station on its service, pass stops included. */
    private OptionalDouble untilNextStation(JourneySnapshot journey, Instant now) {
        Service service = journey.service() == null ? null : store.network().service(journey.service()).orElse(null);
        if (service == null) {
            return OptionalDouble.empty();
        }
        int next = journey.phase() == JourneyPhase.COMPLETED ? -1 : service.nextIndex(journey.lastIndex());
        return next < 0 ? OptionalDouble.empty() : eta.secondsUntil(journey, service, next, now);
    }

    /** The estimator every display asks at this moment: all trains projected together, rebuilt as time moves on. */
    private Estimator estimator(Instant now) {
        if (estimates == null || estimatesAt == null || Duration.between(estimatesAt, now).toMillis() > 400) {
            estimates = ConsistentEstimates.compute(tracker.snapshots(), store.network(), eta, now);
            estimatesAt = now;
        }
        return estimates;
    }
    private final TrackPaths paths = new TrackPaths();
    private final TripLog trips = new TripLog();
    private final BoardService boards;
    private final Map<String, Set<BlockPos>> observedTriggers = new HashMap<>();
    private final List<BukkitTask> tasks = new ArrayList<>();
    /** Writes learned data in order, off the server thread; drained before the final save. */
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ATCS data writer");
        thread.setDaemon(true);
        return thread;
    });
    private final TravelTimes times;
    private final PassengerDisplay display;
    private final ServiceRecorder recorder;
    private AtcsSettings settings;
    private Messages messages;
    private EtaCalculator eta;
    private @Nullable MapLayer map;
    private @Nullable SignLinkPublisher signLink;
    private boolean mapDirty = true;
    private @Nullable WebSite web;
    private boolean webDirty = true;
    private int webWrites;
    private int drawnPathsVersion = -1;
    private boolean debug;
    private Map<BlockPos, ServiceKey> knownSpawnSigns;
    /** Consecutive visits to stations a train's inferred service does not list. */
    private final Map<String, Integer> offRouteVisits = new HashMap<>();

    public RailwayRuntime(
            Plugin plugin,
            NetworkStore store,
            TrainSource trains,
            @Nullable TrainSpawner spawner,
            IndicatorService.Output indicatorOutput,
            AtcsSettings settings,
            Messages messages,
            Clock clock) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.store = store;
        this.trains = trains;
        this.spawner = spawner;
        this.chunkKeeper = new ChunkKeeper(plugin);
        this.indicators = new IndicatorService(indicatorOutput);
        this.clock = clock;
        this.settings = settings;
        this.messages = messages;
        this.debug = settings.debug();
        Path data = plugin.getDataFolder().toPath().resolve("data");
        this.timingsFile = data.resolve("timings.yml");
        this.pathsFile = data.resolve("track-paths.yml");
        this.tripsFile = data.resolve("trips.yml");
        this.samplesFile = data.resolve("eta-samples.yml");
        this.eventLog = new EventLog(data.resolve("events.log"), settings.logging().maxMegabytes() * 1024L * 1024L,
                writer, logger, settings.logging().events(), settings.logging().positions());
        this.times = new TravelTimes(settings.timing().smoothing());
        this.eta = new EtaCalculator(times, settings.boards().defaultDwellSeconds(), context);
        this.display = new PassengerDisplay(settings.passenger(), messages);
        this.boards = new BoardService(logger);
        this.recorder = new ServiceRecorder(store::network);
        this.knownSpawnSigns = store.network().spawnSigns();
        store.addListener(network -> {
            mapDirty = true;
            webDirty = true;
            boards.invalidate();
            List<JourneyEvent> relocated = tracker.relocate(network, clock.instant());
            if (debug && !relocated.isEmpty()) {
                logger.info("[debug] network changed -> " + relocated);
            }
            assignFromNewSpawnSigns(network);
            refreshHeldChunks(network);
        });
    }

    public void start(@Nullable MapLayer mapLayer, @Nullable SignLinkPublisher signLinkPublisher,
                      @Nullable WebSite webSite) {
        loadData();
        this.map = mapLayer;
        this.signLink = signLinkPublisher;
        this.web = webSite;
        webDirty = true;
        mapDirty = true;
        refreshHeldChunks(store.network());
        schedule();
    }

    public void stop() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        display.clear();
        indicators.shutdown();
        eventLog.close();
        chunkKeeper.release();
        if (map != null) {
            map.close();
            map = null;
        }
        if (signLink != null) {
            signLink.clear();
            signLink = null;
        }
        if (web != null) {
            web.close();
            web = null;
        }
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warning("Learned data was still being written at shutdown");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        saveData(false);
    }

    /** Applies new settings and messages after {@code /atcs reload}. */
    public void reconfigure(AtcsSettings newSettings, Messages newMessages, @Nullable MapLayer mapLayer,
                            @Nullable SignLinkPublisher signLinkPublisher, @Nullable WebSite webSite) {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        if (map != null) {
            map.close();
        }
        if (signLink != null) {
            signLink.clear();
        }
        settings = newSettings;
        messages = newMessages;
        debug = newSettings.debug();
        eventLog.configure(newSettings.logging().events(), newSettings.logging().positions());
        times.setSmoothing(newSettings.timing().smoothing());
        eta = new EtaCalculator(times, newSettings.boards().defaultDwellSeconds(), context);
        estimates = null;
        display.configure(newSettings.passenger(), newMessages);
        boards.invalidate();
        map = mapLayer;
        signLink = signLinkPublisher;
        if (web != null) {
            web.close();
        }
        web = webSite;
        webDirty = true;
        mapDirty = true;
        schedule();
    }

    private void schedule() {
        tasks.add(plugin.getServer().getScheduler().runTaskTimer(plugin, this::checkApproaches, 10L, 10L));
        tasks.add(plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshPassengers, 20L, 20L));
        long boardTicks = settings.boards().refreshTicks();
        tasks.add(plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshBoards, boardTicks, boardTicks));
        long mapTicks = settings.blueMap().trainRefreshTicks();
        tasks.add(plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshMap, 40L, mapTicks));
        long saveTicks = settings.timing().saveIntervalMinutes() * 60L * 20L;
        tasks.add(plugin.getServer().getScheduler().runTaskTimer(plugin, () -> saveData(true), saveTicks, saveTicks));
        tasks.add(plugin.getServer().getScheduler().runTaskTimer(plugin, this::expireJourneys, 1200L, 1200L));
        long fleetTicks = settings.spawning().checkSeconds() * 20L;
        tasks.add(plugin.getServer().getScheduler().runTaskTimer(plugin, this::fleetTick, 60L, fleetTicks));
        if (web != null) {
            long webTicks = settings.web().refreshSeconds() * 20L;
            tasks.add(plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshWeb, 40L, webTicks));
        }
    }

    /**
     * Keeps every fleet at strength, holds spawn chunks, and keeps trains' chunks loaded as
     * configured. Runs every {@code spawning.check-seconds}.
     */
    private void fleetTick() {
        Network network = store.network();
        refreshHeldChunks(network);
        if (settings.trains().keepChunksLoaded()) {
            for (TrainHandle train : trains.loadedTrains()) {
                if (!train.keepsChunksLoaded()) {
                    train.keepChunksLoaded(true);
                }
            }
        }
        if (spawner == null) {
            return;
        }
        Map<ServiceKey, Integer> counts = new HashMap<>();
        for (TrainInfo info : trains.allTrains()) {
            ServiceResolver.resolve(info, network).ifPresent(key -> counts.merge(key, 1, Integer::sum));
        }
        Instant now = clock.instant();
        for (Service service : fleets.due(network, counts, now)) {
            Fleet fleet = Objects.requireNonNull(service.fleet());
            fleets.attempted(service.key(), now);
            TrainSpawner.Result result = spawner.spawn(fleet.spawn(), fleet.direction(), fleet.train());
            if (!result.succeeded()) {
                if (!result.problem().equals(lastSpawnProblem.put(service.key(), result.problem()))) {
                    logger.warning("Could not spawn a train for " + service.key() + ": " + result.problem());
                }
                continue;
            }
            lastSpawnProblem.remove(service.key());
            TrainHandle train = Objects.requireNonNull(result.train());
            assign(train, service.key());
            logger.info("Spawned " + train.name() + " for " + service.key() + " ("
                    + (counts.getOrDefault(service.key(), 0) + 1) + " of " + fleet.trains() + ")");
        }
    }

    /** Holds the chunks of spawn signs and fleet spawn points, or none when switched off. */
    private void refreshHeldChunks(Network network) {
        if (!settings.spawning().keepSpawnChunksLoaded()) {
            chunkKeeper.keep(List.of());
            return;
        }
        List<BlockPos> positions = new ArrayList<>(network.spawnSigns().keySet());
        for (Service service : network.services()) {
            if (service.fleet() != null) {
                positions.add(service.fleet().spawn());
            }
        }
        chunkKeeper.keep(positions);
    }

    /** Positions of ATCS stop and station signs seen so far, per station. */
    public Map<String, Set<BlockPos>> observedTriggers() {
        Map<String, Set<BlockPos>> copy = new HashMap<>();
        observedTriggers.forEach((station, positions) -> copy.put(station, Set.copyOf(positions)));
        return copy;
    }

    /** The {@code atcs indicator} signs currently loaded. */
    public IndicatorService indicators() {
        return indicators;
    }

    public int heldChunks() {
        return chunkKeeper.heldChunks();
    }

    /** Names the train after its service and keeps its chunks loaded, as configured. */
    private void settle(TrainHandle train, ServiceKey key) {
        AtcsSettings.Trains rules = settings.trains();
        if (rules.keepChunksLoaded() && !train.keepsChunksLoaded()) {
            train.keepChunksLoaded(true);
        }
        if (!rules.rename() || !train.hasGeneratedName()) {
            return;
        }
        Network network = store.network();
        Service service = network.service(key).orElse(null);
        Line line = network.line(key.lineId()).orElse(null);
        if (service == null || line == null) {
            return;
        }
        String name = TrainNamer.firstFree(rules.nameFormat(), line, service, trains::nameTaken);
        if (!name.isEmpty() && !name.equals(train.name())) {
            renameTrain(train, name);
        }
    }

    private void renameTrain(TrainHandle train, String newName) {
        String old = train.name();
        if (!train.rename(newName)) {
            return;
        }
        tracker.rename(old, newName);
        trips.rename(old, newName);
        samples.rename(old, newName);
        display.rename(old, newName);
        recorder.rename(old, newName);
        Integer offRoute = offRouteVisits.remove(old);
        if (offRoute != null) {
            offRouteVisits.put(newName, offRoute);
        }
        if (debug) {
            logger.info("[debug] " + old + " renamed to " + newName);
        }
    }

    // ---------------------------------------------------------------- adapter callbacks

    /**
     * A train entered or left the rail of a platform trigger sign. Returns what the journey tracker
     * made of it, for the sign to act on.
     */
    public List<JourneyEvent> platformSignal(TrainHandle train, PlatformRef platform, BlockPos trigger, boolean entering) {
        Network network = store.network();
        if (network.platformAt(trigger).isEmpty() && network.station(platform.stationId()).isPresent()
                && observedTriggers.computeIfAbsent(platform.stationId(), ignored -> new LinkedHashSet<>()).add(trigger)) {
            mapDirty = true;
        }
        ServiceKey resolved = ServiceResolver.resolve(train.info(), network).orElse(null);
        Instant now = clock.instant();
        StopObservation observation = new StopObservation(train.name(), platform, trigger, entering, now);
        List<JourneyEvent> events = tracker.observe(observation, resolved, network);
        if (debug) {
            logger.info("[debug] " + train.name() + (entering ? " entered " : " left ") + platform
                    + " at " + trigger.coordinates() + " (service " + resolved + ") -> " + events);
        }
        handle(train, events);
        recorder.onPlatform(train.name(), platform, entering);
        return events;
    }

    // ---------------------------------------------------------------- station and switch signs

    public StationControl control() {
        return control;
    }

    /**
     * Whether a train that just entered an {@code atcs station} sign stops there, and how. Returns
     * null when it runs through.
     *
     * @param events what the arrival produced in the journey tracker
     */
    public StationControl.@Nullable Plan planStop(TrainHandle train, PlatformRef platform, List<JourneyEvent> events,
                                                  StopOptions options) {
        AtcsSettings.Control rules = settings.control();
        StationControl.Decision decision = StationControl.decide(platform, events,
                tracker.snapshot(train.name()).orElse(null), store.network(), rules.rules());
        if (debug) {
            logger.info("[debug] " + train.name() + (decision.stops() ? " stops at " : " runs through ") + platform
                    + ": " + decision.reason());
        }
        if (!decision.stops()) {
            return null;
        }
        StationControl.Plan plan = StationControl.Plan.of(options, rules.rules());
        control.standing(train.name(), platform, plan, clock.instant());
        estimates = null;
        return plan;
    }

    /** Why a train standing at a station sign must keep standing, or empty when it may leave. */
    public Optional<String> holdReason(TrainHandle train, PlatformRef platform, StationControl.Plan plan) {
        Optional<String> reason = control.holdReason(train.name(), platform, tracker.snapshots(), store.network(), plan, clock.instant());
        String previous = holdReasons.put(train.name(), reason.orElse(""));
        if (!reason.orElse("").equals(previous)) {
            eventLog.hold(train.name(), platform, reason.orElse(null), clock.instant());
            if (debug) {
                logger.info("[debug] " + train.name() + (reason.isPresent() ? " held at " + platform + ": " + reason.get()
                        : " may leave " + platform));
            }
        }
        return reason;
    }

    /** A switch sign chose a direction its junction does not have. */
    public void noJunction(TrainHandle train, Heading heading) {
        if (debug) {
            logger.info("[debug] " + train.name() + ": the switch has no junction " + heading.word());
        }
    }

    public void heldTooLong(TrainHandle train, PlatformRef platform, String reason) {
        logger.warning(train.name() + " was held at " + platform + " for " + Math.round(settings.control().rules().maxHoldSeconds())
                + " s (" + reason + ") and is let go; check the train ahead or lower control.max-hold-seconds");
    }

    /**
     * The direction an {@code atcs switch} sign sends a train: by its service and by the station
     * it reaches next on that service.
     */
    public Optional<Heading> switchHeading(TrainHandle train, SwitchRules rules) {
        Network network = store.network();
        JourneySnapshot journey = tracker.snapshot(train.name()).orElse(null);
        ServiceKey key = journey != null && journey.service() != null
                ? journey.service()
                : ServiceResolver.resolve(train.info(), network).orElse(null);
        String next = null;
        if (key != null) {
            Service service = network.service(key).orElse(null);
            if (service != null) {
                int index = journey == null || journey.phase() == JourneyPhase.IDLE ? service.nextIndex(-1)
                        : journey.phase() == JourneyPhase.COMPLETED ? -1
                        : service.nextIndex(journey.lastIndex());
                next = index < 0 ? null : service.stop(index).stationId();
            }
        }
        Optional<Heading> heading = rules.choose(key, next, network);
        if (debug) {
            logger.info("[debug] " + train.name() + " at a switch (service " + key + ", next " + next + ") -> "
                    + heading.map(Heading::word).orElse("no rule, left as is"));
        }
        return heading;
    }

    public boolean setsDestinations() {
        return settings.control().setDestinations();
    }

    /**
     * Keeps the train's TrainCarts destination at its next calling stop, named
     * {@code station:platform} like the station signs register themselves. The planned platform
     * is used, else the one the service's trains usually use, else the station's first platform.
     */
    private void updateDestination(TrainHandle train, Network network) {
        JourneySnapshot journey = tracker.snapshot(train.name()).orElse(null);
        if (journey == null || journey.service() == null) {
            return;
        }
        Service service = network.service(journey.service()).orElse(null);
        if (service == null) {
            return;
        }
        int next = journey.nextCallingIndex(service);
        if (next < 0) {
            return;
        }
        Stop stop = service.stop(next);
        String platform = stop.platformId();
        if (platform == null) {
            platform = times.usualPlatform(service, next).orElse(null);
        }
        if (platform == null) {
            platform = network.station(stop.stationId())
                    .flatMap(station -> station.platforms().keySet().stream().findFirst())
                    .orElse("1");
        }
        String destination = stop.stationId() + ":" + platform;
        if (!destination.equals(train.info().destination())) {
            train.setDestination(destination);
            if (debug) {
                logger.info("[debug] " + train.name() + " destination set to " + destination);
            }
        }
    }

    /**
     * TrainCarts is creating a train and has not set it up yet. Keep-chunks-loaded has to go on
     * here, before TrainCarts works out which chunks the train occupies: it holds those chunks only
     * for a train that already has the property. Switched on a tick later, a train still standing
     * at its spawn point (TrainCarts' only-when-moving rule) would occupy chunks that nothing
     * holds, and TrainCarts logs "unloaded unexpectedly" when they go.
     */
    public void trainCreating(TrainHandle train) {
        if (settings.trains().keepChunksLoaded() && !train.keepsChunksLoaded()) {
            train.keepChunksLoaded(true);
        }
    }

    /**
     * A train was created, by a TrainCarts spawn sign at {@code spawnSign} if known. Trains from a
     * spawn sign listed by a service are assigned to it, unless they already carry an assignment.
     */
    public void trainCreated(TrainHandle train, @Nullable BlockPos spawnSign) {
        trips.started(train.name(), spawnSign);
        if (debug) {
            logger.info("[debug] " + train.name() + " created" + (spawnSign == null ? "" : " by spawn sign at " + spawnSign.coordinates()));
        }
        Network network = store.network();
        Set<String> tags = train.info().tags();
        boolean assigned = ServiceResolver.taggedService(tags)
                .filter(key -> network.service(key).isPresent())
                .isPresent();
        if (spawnSign == null || assigned || ServiceResolver.isManual(tags)) {
            return;
        }
        network.serviceSpawnedAt(spawnSign).ifPresent(service -> assign(train, service));
    }

    /** A train that had been unloaded is loaded again: the same journey continues, no new trip. */
    public void trainReloaded(TrainHandle train) {
        tracker.touch(train.name(), clock.instant());
        if (debug) {
            logger.info("[debug] " + train.name() + " reloaded");
        }
    }

    /**
     * Assigns running trains created by a spawn sign that a service newly lists, so that binding a
     * spawn sign (or applying an import) takes effect immediately rather than with the next train.
     * Existing bindings are not re-applied: a train cleared on purpose stays cleared.
     */
    private void assignFromNewSpawnSigns(Network network) {
        Map<BlockPos, ServiceKey> current = network.spawnSigns();
        Map<BlockPos, ServiceKey> added = new HashMap<>();
        current.forEach((sign, service) -> {
            if (!service.equals(knownSpawnSigns.get(sign))) {
                added.put(sign, service);
            }
        });
        knownSpawnSigns = current;
        if (added.isEmpty()) {
            return;
        }
        for (TrainHandle train : trains.loadedTrains()) {
            BlockPos sign = trips.spawnSignOf(train.name());
            ServiceKey service = sign == null ? null : added.get(sign);
            if (service != null && ServiceResolver.resolve(train.info(), network).isEmpty()
                    && !ServiceResolver.isManual(train.info().tags())) {
                assign(train, service);
            }
        }
    }

    public void trainRemoved(String name) {
        offRouteVisits.remove(name);
        trips.ended(name);
        if (debug) {
            logger.info("[debug] " + name + " removed");
        }
        tracker.forget(name);
        samples.forget(name);
        control.left(name);
        trainStates.remove(name);
        holdReasons.remove(name);
        paths.forget(name);
        display.remove(name);
        recorder.trainRemoved(name);
        indicators.refresh(tracker.snapshots(), store.network());
    }

    public void trainUnloaded(String name) {
        paths.forget(name);
        display.remove(name);
    }

    public void riderBoarded(TrainHandle train, Player player) {
        display.boarded(player, view(train.name()).orElse(null), store.network());
    }

    public void riderLeft(Player player) {
        display.removePlayer(player);
    }

    /** Whether head positions of this train are wanted for track-path recording. */
    public boolean recordingPath(String trainName) {
        return paths.isRecording(trainName);
    }

    public void headMoved(String trainName, String world, double x, double y, double z) {
        paths.sample(trainName, world, x, y, z);
    }

    // ---------------------------------------------------------------- commands and queries

    /** Assigns a service explicitly (command, ATCS sign, spawn sign) and stores it on the train. */
    public List<JourneyEvent> assign(TrainHandle train, ServiceKey service) {
        return assign(train, service, false);
    }

    private List<JourneyEvent> assign(TrainHandle train, ServiceKey service, boolean inferred) {
        train.setAssignment(service, inferred);
        settle(train, service);
        offRouteVisits.remove(train.name());
        List<JourneyEvent> events = tracker.assign(train.name(), service, store.network(), clock.instant());
        if (debug) {
            logger.info("[debug] " + train.name() + (inferred ? " inferred " : " assigned ") + service + " -> " + events);
        }
        handle(train, events);
        return events;
    }

    /**
     * Removes a train's service. With {@code manual} the train stays without one until an operator
     * assigns it again; otherwise spawn signs, match rules and route inference apply as usual.
     */
    public List<JourneyEvent> unassign(TrainHandle train, boolean manual) {
        train.clearAssignment(manual);
        offRouteVisits.remove(train.name());
        List<JourneyEvent> events = tracker.assign(train.name(), null, store.network(), clock.instant());
        if (debug) {
            logger.info("[debug] " + train.name() + (manual ? " set to manual" : " set to automatic") + " -> " + events);
        }
        handle(train, events);
        return events;
    }

    /**
     * Gives a train without a service the one its recent stops identify, and re-infers a train
     * whose inferred service no longer explains where it goes.
     */
    private void inferService(TrainHandle train, Network network) {
        AtcsSettings.Assignment rules = settings.assignment();
        if (!rules.inferFromRoute()) {
            return;
        }
        Set<String> tags = train.info().tags();
        if (ServiceResolver.isManual(tags)) {
            return;
        }
        JourneySnapshot journey = tracker.snapshot(train.name()).orElse(null);
        if (journey == null) {
            return;
        }
        if (journey.service() != null && (!ServiceResolver.isInferred(tags)
                || offRouteVisits.getOrDefault(train.name(), 0) < rules.minStops())) {
            return;
        }
        Optional<ServiceKey> match = RouteMatcher.infer(
                trips.recentStations(train.name(), RouteMatcher.MAX_WINDOW), network, rules.minStops());
        if (match.isPresent()) {
            if (!match.get().equals(journey.service())) {
                assign(train, match.get(), true);
            }
        } else if (journey.service() != null) {
            unassign(train, false);
        }
    }

    public Optional<JourneySnapshot> journey(String trainName) {
        return tracker.snapshot(trainName);
    }

    public Optional<TrainView> view(String trainName) {
        Instant now = clock.instant();
        return tracker.snapshot(trainName)
                .flatMap(journey -> TrainView.of(journey, store.network(), times, estimator(now), context, now));
    }

    public List<Departures.Departure> departures(String stationId, Predicate<String> platformFilter) {
        return departures(stationId, platformFilter, tracker.snapshots());
    }

    private List<Departures.Departure> departures(String stationId, Predicate<String> platformFilter,
                                                  Collection<JourneySnapshot> journeys) {
        Instant now = clock.instant();
        return Departures.from(stationId, platformFilter, journeys, store.network(), times, estimator(now), now);
    }

    public TravelTimes times() {
        return times;
    }

    public ServiceRecorder recorder() {
        return recorder;
    }

    public TrackPaths paths() {
        return paths;
    }

    /** Arrival estimates scored against real arrivals. */
    public EtaSamples timing() {
        return samples;
    }

    public TripLog trips() {
        return trips;
    }

    public Collection<JourneySnapshot> journeys() {
        return tracker.snapshots();
    }

    public @Nullable MapLayer map() {
        return map;
    }

    /** Where the website is written, when it is. */
    public Optional<Path> webFolder() {
        WebSite site = web;
        return site != null && site.ready() ? Optional.ofNullable(site.folder()) : Optional.empty();
    }

    public boolean webConfigured() {
        return web != null;
    }

    public boolean signLinkActive() {
        return signLink != null;
    }

    public boolean debug() {
        return debug;
    }

    public void setDebug(boolean enabled) {
        debug = enabled;
    }

    public void redrawMap() {
        mapDirty = true;
    }

    public void clearPaths(ServiceKey key) {
        paths.clear(key);
        mapDirty = true;
    }

    public StationLocator locator() {
        return new Locator(store.network());
    }

    private @Nullable Supplier<Map<String, Object>> fareSummary;

    /** What the website shows about fares; set by the plugin once the fare service exists. */
    public void setFareSummary(@Nullable Supplier<Map<String, Object>> supplier) {
        fareSummary = supplier;
        webDirty = true;
    }

    /** Fares were edited: the website's network data is written again. */
    public void fareTableChanged() {
        webDirty = true;
    }

    /**
     * Track length from a stop to the next: along the recorded path where one exists, else the
     * straight line between the two stops' positions, else unknown.
     */
    public RouteMeasure measure() {
        return (service, index) -> {
            Optional<TrackPaths.Path> path = paths.path(service, index);
            if (path.isPresent() && path.get().points().size() >= 2) {
                double length = 0;
                List<TrackPaths.Point> points = path.get().points();
                for (int point = 0; point + 1 < points.size(); point++) {
                    TrackPaths.Point a = points.get(point);
                    TrackPaths.Point b = points.get(point + 1);
                    length += Math.sqrt(Math.pow(a.x() - b.x(), 2) + Math.pow(a.y() - b.y(), 2) + Math.pow(a.z() - b.z(), 2));
                }
                return OptionalDouble.of(length);
            }
            int next = service.nextIndex(index);
            if (next < 0) {
                return OptionalDouble.empty();
            }
            Network network = store.network();
            Locator locator = new Locator(network);
            Optional<BlockPos> from = network.station(service.stop(index).stationId()).flatMap(station -> locator.stop(station, service.stop(index)));
            Optional<BlockPos> to = network.station(service.stop(next).stationId()).flatMap(station -> locator.stop(station, service.stop(next)));
            if (from.isEmpty() || to.isEmpty() || !from.get().world().equals(to.get().world())) {
                return OptionalDouble.empty();
            }
            return OptionalDouble.of(Math.sqrt(from.get().distanceSquared(to.get())));
        };
    }

    // ---------------------------------------------------------------- internals

    private void handle(TrainHandle train, List<JourneyEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        Network network = store.network();
        for (JourneyEvent event : events) {
            times.record(event, network);
            trips.record(event);
            eventLog.journey(event);
            if (event instanceof JourneyEvent.Arrived arrived) {
                samples.arrived(train.name(), arrived.service(), arrived.stopIndex(), arrived.at());
            } else if (event instanceof JourneyEvent.ServiceChanged) {
                samples.forget(train.name());
            }
            switch (event) {
                case JourneyEvent.Passed passed when passed.stopIndex() < 0 ->
                        offRouteVisits.merge(train.name(), 1, Integer::sum);
                case JourneyEvent.Passed ignored -> offRouteVisits.remove(train.name());
                case JourneyEvent.Arrived ignored -> offRouteVisits.remove(train.name());
                default -> {
                }
            }
            if (settings.blueMap().recordTrackPaths()) {
                paths.onEvent(event, network);
            }
            if (event instanceof JourneyEvent.Departed departed) {
                control.departed(departed);
            } else if (event instanceof JourneyEvent.UnassignedDeparted) {
                control.left(train.name());
            }
            estimates = null;
            if (event instanceof JourneyEvent.ServiceChanged changed && changed.to() != null) {
                Set<String> tags = train.info().tags();
                Optional<ServiceKey> tagged = ServiceResolver.taggedService(tags);
                if (tagged.isPresent() && !tagged.get().equals(changed.to())) {
                    // Keep the train's tag in step when it continues as another service.
                    train.setAssignment(changed.to(), ServiceResolver.isInferred(tags));
                }
            }
        }
        inferService(train, network);
        if (settings.control().setDestinations()) {
            updateDestination(train, network);
        }
        indicators.refresh(tracker.snapshots(), network);
        TrainView view = view(train.name()).orElse(null);
        List<Player> riders = train.riders();
        if (riders.isEmpty()) {
            return;
        }
        for (int index = 0; index < events.size(); index++) {
            JourneyEvent event = events.get(index);
            if (event instanceof JourneyEvent.Departed && laterArrival(events, index)) {
                continue;
            }
            display.announce(event, view, riders, network);
        }
        display.refresh(train.name(), view, riders, network);
    }

    private static boolean laterArrival(List<JourneyEvent> events, int index) {
        for (int later = index + 1; later < events.size(); later++) {
            if (events.get(later) instanceof JourneyEvent.Arrived
                    || events.get(later) instanceof JourneyEvent.UnassignedArrived) {
                return true;
            }
        }
        return false;
    }

    private void checkApproaches() {
        double distance = settings.passenger().approachDistance();
        if (distance <= 0 || !settings.passenger().enabled()) {
            return;
        }
        Network network = store.network();
        Locator locator = new Locator(network);
        double limit = distance * distance;
        Instant now = clock.instant();
        for (TrainHandle train : trains.loadedTrains()) {
            JourneySnapshot journey = tracker.snapshot(train.name()).orElse(null);
            if (journey == null || journey.service() == null
                    || (journey.phase() != JourneyPhase.IN_TRANSIT && journey.phase() != JourneyPhase.IDLE)) {
                continue;
            }
            Service service = network.service(journey.service()).orElse(null);
            if (service == null) {
                continue;
            }
            int next = journey.nextCallingIndex(service);
            Location head = next < 0 ? null : train.headLocation();
            if (head == null || head.getWorld() == null) {
                continue;
            }
            Stop stop = service.stop(next);
            boolean near = locator.positions(stop).stream().anyMatch(position ->
                    position.world().equals(head.getWorld().getName())
                            && position.distanceSquared(head.getX(), head.getY(), head.getZ()) <= limit);
            if (near) {
                tracker.markApproaching(train.name(), network, now).ifPresent(event -> {
                    if (debug) {
                        logger.info("[debug] " + train.name() + " approaching " + stop.stationId());
                    }
                    handle(train, List.of(event));
                });
            }
        }
    }

    /** Scores the estimates shown now against the arrivals that follow, and logs positions when asked. */
    private void observeTrains(Network network) {
        Instant now = clock.instant();
        tick++;
        Set<String> loaded = new HashSet<>();
        for (TrainHandle train : trains.loadedTrains()) {
            loaded.add(train.name());
            Location head = train.headLocation();
            if (head == null || head.getWorld() == null) {
                continue;
            }
            double speed = train.speed();
            TrainState previous = trainStates.get(train.name());
            Instant standingSince = speed >= STANDING_SPEED ? null
                    : previous != null && previous.standingSince() != null ? previous.standingSince() : now;
            TrainState state = new TrainState(head.getWorld().getName(), head.getX(), head.getY(), head.getZ(), speed, now, standingSince);
            trainStates.put(train.name(), state);
            JourneySnapshot journey = tracker.snapshot(train.name()).orElse(null);
            if (eventLog.positions() && tick % 2 == 0 && journey != null && journey.phase() == JourneyPhase.IN_TRANSIT) {
                eventLog.position(train.name(), journey.service() == null ? null : journey.service().toString(),
                        state.world(), state.x(), state.y(), state.z(), speed, now);
            }
        }
        trainStates.keySet().retainAll(loaded);
        Estimator shown = estimator(now);
        for (JourneySnapshot journey : tracker.snapshots()) {
            if (journey.service() == null || journey.phase() == JourneyPhase.COMPLETED) {
                continue;
            }
            Service service = network.service(journey.service()).orElse(null);
            if (service == null) {
                continue;
            }
            int next = journey.nextCallingIndex(service);
            if (next < 0) {
                continue;
            }
            shown.secondsUntil(journey, service, next, now)
                    .ifPresent(seconds -> samples.predicted(journey.trainId(), service.key(), next, seconds, now));
        }
    }

    private void refreshPassengers() {
        Network network = store.network();
        observeTrains(network);
        indicators.refresh(tracker.snapshots(), network);
        Set<String> loaded = new HashSet<>();
        for (TrainHandle train : trains.loadedTrains()) {
            loaded.add(train.name());
            List<Player> riders = train.riders();
            if (riders.isEmpty()) {
                display.remove(train.name());
                continue;
            }
            display.refresh(train.name(), view(train.name()).orElse(null), riders, network);
        }
        for (String name : display.trainsWithBars()) {
            if (!loaded.contains(name)) {
                display.remove(name);
            }
        }
    }

    private void refreshBoards() {
        Network network = store.network();
        if (network.boards().isEmpty() && signLink == null) {
            return;
        }
        Collection<JourneySnapshot> journeys = tracker.snapshots();
        Map<String, List<Departures.Departure>> byStation = new HashMap<>();
        try {
            boards.update(network, messages, (Board board, Integer count) -> departures(
                    board.stationId(), board::includesPlatform, journeys).stream().limit(count).toList());
            if (signLink != null) {
                signLink.publish(network, messages, settings.signLink().departuresPerStation(), stationId ->
                        byStation.computeIfAbsent(stationId, id -> departures(id, platform -> true, journeys)));
            }
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "Could not update departure displays", exception);
        }
    }

    /** Writes the website's data files: the network when it changed (and now and then for learned times), the trains always. */
    private void refreshWeb() {
        WebSite site = web;
        if (site == null || !site.ready()) {
            return;
        }
        Network network = store.network();
        Instant now = clock.instant();
        if (webDirty || webWrites % 20 == 0) {
            webDirty = false;
            Supplier<Map<String, Object>> fares = fareSummary;
            String json = WebModel.network(settings.web().title(), settings.web().refreshSeconds(), network,
                    new Locator(network), times, settings.boards().defaultDwellSeconds(), fares == null ? null : fares.get(), now);
            writer.execute(() -> site.write("network.json", json));
        }
        webWrites++;
        String live = WebModel.live(network, tracker.snapshots(), journey -> view(journey.trainId()),
                station -> departures(station, platform -> true), times, WEB_DEPARTURES, now);
        writer.execute(() -> site.write("live.json", live));
    }

    private void refreshMap() {
        MapLayer layer = map;
        if (layer == null) {
            return;
        }
        Network network = store.network();
        try {
            if (mapDirty || drawnPathsVersion != paths.version() || layer.wantsNetwork()) {
                layer.showNetwork(network, paths, new Locator(network));
                mapDirty = false;
                drawnPathsVersion = paths.version();
            }
            List<MapLayer.TrainMarker> markers = new ArrayList<>();
            for (TrainHandle train : trains.loadedTrains()) {
                TrainView view = view(train.name()).orElse(null);
                Location head = view == null ? null : train.headLocation();
                if (head == null || head.getWorld() == null) {
                    continue;
                }
                String next = view.nextStationId().map(network::stationName).orElse("");
                String label = view.line().shortName() + " " + view.service().name() + " » " + view.destinationName(network);
                String detail = view.completed() ? "Not in service"
                        : view.atStop() && view.currentStationId().isPresent() ? "At " + network.stationName(view.currentStationId().get())
                        : next.isEmpty() ? "" : "Next: " + next
                                + (view.etaNext().isPresent() ? " in " + plainDuration(view.etaNext().getAsDouble()) : "");
                markers.add(new MapLayer.TrainMarker(train.name(), head.getWorld().getName(),
                        head.getX(), head.getY(), head.getZ(), label, detail, view.line().color(), view.line().id(),
                        view.held() || view.holdReason() != null));
            }
            List<MapLayer.StationLive> stations = new ArrayList<>();
            for (Station station : network.stations().values()) {
                List<MapLayer.DepartureLine> lines = new ArrayList<>();
                for (Departures.Departure departure : departures(station.id(), platform -> true)) {
                    if (lines.size() >= 4) {
                        break;
                    }
                    Line line = network.line(departure.service().key().lineId()).orElse(null);
                    String time = departure.status() == Departures.Status.BOARDING ? "Boarding"
                            : departure.etaSeconds().isPresent() ? plainDuration(departure.etaSeconds().getAsDouble()) : "Expected";
                    lines.add(new MapLayer.DepartureLine(line == null ? "" : line.shortName(), line == null ? 0x888888 : line.color(),
                            departure.service().destinationFrom(departure.stopIndex()).map(stop -> network.stationName(stop.stationId()))
                                    .orElse(departure.service().name()), departure.platformId(), time));
                }
                stations.add(new MapLayer.StationLive(station.id(), lines));
            }
            layer.showLive(markers, stations);
        } catch (RuntimeException | LinkageError exception) {
            logger.log(Level.WARNING, layer.name() + " update failed; map output disabled until reload", exception);
            map = null;
            try {
                layer.close();
            } catch (RuntimeException | LinkageError ignored) {
                // Already failing; nothing more to do.
            }
        }
    }

    private String plainDuration(double seconds) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(messages.duration(seconds));
    }

    private void expireJourneys() {
        Set<String> loaded = trains.loadedTrains().stream().map(TrainHandle::name).collect(Collectors.toSet());
        tracker.expire(clock.instant().minus(JOURNEY_EXPIRY), loaded::contains);
    }

    private void loadData() {
        times.load(readYaml(timingsFile));
        paths.load(readYaml(pathsFile));
        trips.load(readYaml(tripsFile));
        samples.load(readYaml(samplesFile));
    }

    private YamlConfiguration readYaml(Path file) {
        YamlConfiguration yaml = new YamlConfiguration();
        if (Files.exists(file)) {
            try {
                yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException | InvalidConfigurationException exception) {
                logger.warning("Ignoring unreadable " + file.getFileName() + " (it will be relearned): "
                        + exception.getMessage());
            }
        }
        return yaml;
    }

    private void saveData(boolean async) {
        Map<Path, String> pending = new HashMap<>();
        if (times.dirty()) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.options().setHeader(List.of("Learned automatically by ATCS. Safe to delete; it is relearned."));
            times.save(yaml);
            pending.put(timingsFile, yaml.saveToString());
        }
        if (paths.dirty()) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.options().setHeader(List.of("Recorded automatically by ATCS. Safe to delete; it is recorded again."));
            paths.save(yaml);
            pending.put(pathsFile, yaml.saveToString());
        }
        if (trips.dirty()) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.options().setHeader(List.of("Train trips observed by ATCS, used by /atcs import services. Safe to delete."));
            trips.save(yaml);
            pending.put(tripsFile, yaml.saveToString());
        }
        if (samples.dirty()) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.options().setHeader(List.of("Arrival estimates scored against real arrivals, for /atcs timing report. Safe to delete."));
            samples.save(yaml);
            pending.put(samplesFile, yaml.saveToString());
        }
        if (pending.isEmpty()) {
            return;
        }
        Runnable write = () -> pending.forEach((file, content) -> {
            try {
                Files2.writeAtomically(file, content);
            } catch (IOException exception) {
                logger.log(Level.WARNING, "Could not save " + file.getFileName(), exception);
            }
        });
        if (async && !writer.isShutdown()) {
            writer.execute(write);
        } else {
            write.run();
        }
    }

    /** Positions of stations and stops, from bound triggers and observed ATCS stop signs. */
    private final class Locator implements StationLocator {
        private final Network network;

        private Locator(Network network) {
            this.network = network;
        }

        @Override
        public Optional<BlockPos> station(Station station) {
            Optional<BlockPos> configured = station.markerPosition();
            if (configured.isPresent()) {
                return configured;
            }
            Set<BlockPos> observed = observedTriggers.get(station.id());
            return observed == null || observed.isEmpty() ? Optional.empty() : Optional.of(observed.iterator().next());
        }

        @Override
        public Optional<BlockPos> stop(Station station, Stop stop) {
            if (stop.platformId() != null) {
                Optional<BlockPos> platform = station.platform(stop.platformId())
                        .flatMap(value -> value.triggers().stream().findFirst());
                if (platform.isPresent()) {
                    return platform;
                }
            }
            return station(station);
        }

        private List<BlockPos> positions(Stop stop) {
            Station station = network.station(stop.stationId()).orElse(null);
            if (station == null) {
                return List.of();
            }
            List<BlockPos> result = new ArrayList<>();
            for (Platform platform : station.platforms().values()) {
                if (stop.platformId() == null || stop.platformId().equals(platform.id())) {
                    result.addAll(platform.triggers());
                }
            }
            if (result.isEmpty()) {
                result.addAll(observedTriggers.getOrDefault(station.id(), Set.of()));
            }
            return result;
        }
    }
}
