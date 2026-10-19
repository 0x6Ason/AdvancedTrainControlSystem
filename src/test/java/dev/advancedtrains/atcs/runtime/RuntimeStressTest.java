package dev.advancedtrains.atcs.runtime;

import dev.advancedtrains.atcs.config.AtcsSettings;
import dev.advancedtrains.atcs.config.Messages;
import dev.advancedtrains.atcs.control.StationControl;
import dev.advancedtrains.atcs.control.StopOptions;
import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.NetworkStore;
import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.operation.JourneyEvent;
import dev.advancedtrains.atcs.operation.ServiceResolver;
import dev.advancedtrains.atcs.operation.TrackPaths;
import dev.advancedtrains.atcs.operation.TrainInfo;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Logger;

import static dev.advancedtrains.atcs.TestNetworks.network;
import static dev.advancedtrains.atcs.TestNetworks.platform;
import static dev.advancedtrains.atcs.TestNetworks.service;
import static dev.advancedtrains.atcs.TestNetworks.trigger;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole runtime, wired as the plugin wires it, with every control feature on at once: even
 * spacing, headway, section holds, timetabled departures and junction priority, on a loop line
 * that shares a stretch of track with a branch, while boards and the website keep asking the
 * estimator. The two stack overflows of 2026-10-17 (68c2936, 36302ab) came from chains of such
 * questions; this drives three trains round the loop for five minutes and checks that every
 * question is answered, every hold ends, and each rule had its say.
 */
class RuntimeStressTest {
    private static final Logger LOG = Logger.getLogger("test");
    private static final Instant T0 = Instant.parse("2026-10-17T10:00:00Z");
    private static final ServiceKey LOOP = ServiceKey.parse("5/cw");
    private static final ServiceKey BRANCH = ServiceKey.parse("a/east");
    private static final ServiceKey BACK = ServiceKey.parse("a/west");
    private static final double BLOCKS_PER_SECOND = 6;

    @TempDir
    Path folder;
    private final MutableClock clock = new MutableClock(T0);
    private final List<Runnable> tasks = new ArrayList<>();
    private final List<FakeTrain> trains = new ArrayList<>();
    private World world;
    private RailwayRuntime runtime;
    private Network network;

    // ---------------------------------------------------------------- fakes

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant at) {
            now = at;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** A train with a position the test moves, standing or rolling along a recorded path. */
    final class FakeTrain implements TrainHandle {
        final String name;
        final Set<String> tags = new LinkedHashSet<>();
        String destination = "";
        double x;
        double z;
        double speed;
        /** The path the train rolls along, or null while it stands; how far along it is. */
        @Nullable List<TrackPaths.Point> path;
        double travelled;
        /** While standing: where, since when, and the plan the station sign made. */
        @Nullable PlatformRef at;
        @Nullable Instant since;
        StationControl.@Nullable Plan plan;
        int index;
        Service service;
        /** Standing with nothing to move it; whether a launch gets it going; how often it was launched. */
        boolean stuck;
        boolean canMove = true;
        int launches;
        List<Player> aboard = List.of();

        FakeTrain(String name, Service service, double x, double z) {
            this.name = name;
            this.service = service;
            this.x = x;
            this.z = z;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public TrainInfo info() {
            return TrainInfo.of(name, tags, destination, List.of());
        }

        @Override
        public @Nullable Location headLocation() {
            return new Location(world, x, 64, z);
        }

        @Override
        public double speed() {
            return speed;
        }

        @Override
        public List<Player> riders() {
            return aboard;
        }

        @Override
        public boolean stuck() {
            return stuck && speed == 0;
        }

        @Override
        public void sendOn() {
            launches++;
            if (canMove) {
                stuck = false;
                speed = BLOCKS_PER_SECOND / 20;
            }
        }

        @Override
        public void setAssignment(ServiceKey key, boolean inferred) {
            tags.removeIf(tag -> ServiceResolver.isAssignmentTag(tag) || ServiceResolver.isInferredTag(tag));
            tags.add(ServiceResolver.assignmentTag(key));
            if (inferred) {
                tags.add(ServiceResolver.TAG_INFERRED);
            }
        }

        @Override
        public void clearAssignment(boolean manual) {
            tags.removeIf(tag -> ServiceResolver.isAssignmentTag(tag) || ServiceResolver.isInferredTag(tag));
        }

        @Override
        public void setDestination(String newDestination) {
            destination = newDestination;
        }

        @Override
        public void markForDepot() {
            clearAssignment(false);
            tags.add(ServiceResolver.depotTag());
        }

        @Override
        public boolean hasGeneratedName() {
            return false;
        }

        @Override
        public boolean rename(String newName) {
            return false;
        }

        @Override
        public boolean keepsChunksLoaded() {
            return true;
        }

        @Override
        public double speedLimit() {
            return 0.4;
        }

        @Override
        public void setSpeedLimit(double blocksPerTick) {
        }

        @Override
        public void keepChunksLoaded(boolean keep) {
        }

        void standAt(String station, int stopIndex) {
            path = null;
            speed = 0;
            BlockPos sign = trigger(station);
            x = sign.x();
            z = sign.z();
            index = stopIndex;
        }
    }

    final class Yard implements TrainSource, TrainSpawner {
        @Override
        public Collection<TrainHandle> loadedTrains() {
            return List.copyOf(trains);
        }

        @Override
        public Result spawn(BlockPos at, dev.advancedtrains.atcs.network.Fleet.Direction direction, String pattern) {
            return Result.failed("no spawning here");
        }

        @Override
        public boolean destroy(TrainHandle train) {
            if (!trains.remove(train)) {
                return false;
            }
            runtime.trainRemoved(train.name());
            return true;
        }

        @Override
        public Collection<TrainInfo> allTrains() {
            return trains.stream().map(FakeTrain::info).toList();
        }

        @Override
        public boolean nameTaken(String name) {
            return trains.stream().anyMatch(train -> train.name.equals(name));
        }

        @Override
        public Optional<TrainHandle> train(String name) {
            return trains.stream().filter(train -> train.name.equals(name)).map(TrainHandle.class::cast).findFirst();
        }

        @Override
        public Optional<TrainHandle> trainOf(Player player) {
            return Optional.empty();
        }

        @Override
        public Optional<TrainHandle> trainLookedAt(Player player) {
            return Optional.empty();
        }
    }

    /** A Bukkit interface answering from the map, by method name, and with blanks otherwise. */
    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> type, Map<String, Object> answers) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "fake " + type.getSimpleName();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            }
            if (answers.containsKey(method.getName())) {
                Object answer = answers.get(method.getName());
                return answer instanceof Function<?, ?> function ? ((Function<Object[], Object>) function).apply(args) : answer;
            }
            Class<?> returns = method.getReturnType();
            if (returns == boolean.class) {
                return false;
            }
            if (returns == int.class || returns == long.class || returns == short.class || returns == byte.class) {
                return returns == long.class ? 0L : returns == short.class ? (short) 0 : returns == byte.class ? (byte) 0 : 0;
            }
            if (returns == double.class || returns == float.class) {
                return returns == float.class ? 0f : 0d;
            }
            if (returns == char.class) {
                return '\0';
            }
            if (returns == String.class) {
                return "fake";
            }
            if (returns == Logger.class) {
                return LOG;
            }
            if (List.class.isAssignableFrom(returns) || Collection.class == returns) {
                return List.of();
            }
            if (Set.class.isAssignableFrom(returns)) {
                return Set.of();
            }
            if (Map.class.isAssignableFrom(returns)) {
                return Map.of();
            }
            if (returns.isInterface()) {
                return fake(returns, Map.of());
            }
            return null;
        }));
    }

    private Plugin plugin() {
        BukkitScheduler scheduler = fake(BukkitScheduler.class, Map.of("runTaskTimer", (Function<Object[], Object>) args -> {
            tasks.add((Runnable) args[1]);
            return fake(BukkitTask.class, Map.of());
        }));
        world = fake(World.class, Map.of("getName", "world"));
        Server server = fake(Server.class, Map.of("getScheduler", scheduler, "getLogger", LOG, "getName", "Fake", "getVersion", "0",
                "getBukkitVersion", "0", "getWorlds", List.of(world), "getWorld", (Function<Object[], Object>) args -> world));
        // Bukkit's static server is never set: paper-api needs a build-info service for that,
        // and nothing on the paths driven here asks Bukkit for anything.
        return fake(Plugin.class, Map.of("getLogger", LOG, "getDataFolder", folder.toFile(), "getServer", server, "getName", "ATCS"));
    }

    private static YamlConfiguration bundled(String name) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(
                Objects.requireNonNull(RuntimeStressTest.class.getResourceAsStream("/" + name)), StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    // ---------------------------------------------------------------- the railway

    private static TrackPaths.Point point(double x, double z) {
        return new TrackPaths.Point(x + 0.5, 64, z + 0.5);
    }

    /** Records a run between two stops of a service as straight stretches between the corners. */
    private void record(TrackPaths paths, ServiceKey key, int from, List<TrackPaths.Point> corners) {
        Service service = network.service(key).orElseThrow();
        int to = service.nextIndex(from);
        String train = "recorder";
        paths.onEvent(new JourneyEvent.Departed(train, T0.minusSeconds(3600), key, from, platform(service.stop(from).stationId()), to, null), network);
        for (TrackPaths.Point point : walk(corners)) {
            paths.sample(train, "world", point.x(), point.y(), point.z());
        }
        paths.onEvent(new JourneyEvent.Arrived(train, T0.minusSeconds(3500), key, to, platform(service.stop(to).stationId()), false, from,
                Duration.ofSeconds(60)), network);
    }

    /** One point per block along the corners. */
    private static List<TrackPaths.Point> walk(List<TrackPaths.Point> corners) {
        List<TrackPaths.Point> points = new ArrayList<>();
        for (int index = 0; index + 1 < corners.size(); index++) {
            TrackPaths.Point a = corners.get(index);
            TrackPaths.Point b = corners.get(index + 1);
            int steps = (int) Math.round(Math.max(Math.abs(b.x() - a.x()), Math.abs(b.z() - a.z())));
            for (int step = 0; step <= steps; step++) {
                double t = steps == 0 ? 0 : (double) step / steps;
                points.add(new TrackPaths.Point(a.x() + (b.x() - a.x()) * t, 64, a.z() + (b.z() - a.z()) * t));
            }
        }
        return points;
    }

    private final Map<ServiceKey, Map<Integer, List<TrackPaths.Point>>> routes = new HashMap<>();

    private void route(ServiceKey key, int from, TrackPaths.Point... corners) {
        List<TrackPaths.Point> points = walk(List.of(corners));
        routes.computeIfAbsent(key, ignored -> new HashMap<>()).put(from, points);
        record(runtime.paths(), key, from, List.of(corners));
    }

    private List<JourneyEvent> enter(FakeTrain train, String station) {
        return runtime.platformSignal(train, new PlatformRef(station, "1"), trigger(station), true);
    }

    private void leave(FakeTrain train, String station) {
        runtime.platformSignal(train, new PlatformRef(station, "1"), trigger(station), false);
    }

    /** The train arrives at stop {@code index} of its service and the station sign plans its stop. */
    private void arrive(FakeTrain train, int index, String options) {
        String station = train.service.stop(index).stationId();
        train.standAt(station, index);
        List<JourneyEvent> events = enter(train, station);
        train.at = new PlatformRef(station, "1");
        train.since = clock.instant();
        train.plan = runtime.planStop(train, train.at, events, StopOptions.parse(options));
    }

    /** The train leaves its stop and rolls towards the next one along the recorded path. */
    private void depart(FakeTrain train) {
        leave(train, train.at.stationId());
        train.path = routes.get(train.service.key()).get(train.index);
        train.travelled = 0;
        train.speed = BLOCKS_PER_SECOND / 20;
        train.at = null;
        train.plan = null;
    }

    private void roll(FakeTrain train, double blocksPerSecond) {
        if (train.path == null) {
            return;
        }
        train.travelled += blocksPerSecond;
        int step = (int) Math.min(train.path.size() - 1, Math.round(train.travelled));
        train.x = train.path.get(step).x() - 0.5;
        train.z = train.path.get(step).z() - 0.5;
    }

    private boolean atEndOfPath(FakeTrain train) {
        return train.path != null && train.travelled >= train.path.size() - 1;
    }

    // ---------------------------------------------------------------- the test

    /** The runtime as the plugin wires it, with every control feature on, over this network. */
    private void start(Network railway) throws Exception {
        start(railway, config -> { });
    }

    private void start(Network railway, java.util.function.Consumer<YamlConfiguration> tweak) throws Exception {
        network = railway;
        YamlConfiguration config = bundled("config.yml");
        config.set("control.dwell-seconds", 10);
        config.set("control.headway-seconds", 30);
        config.set("control.hold-for-clear-section", true);
        config.set("control.max-hold-seconds", 60);
        config.set("control.even-spacing", true);
        config.set("control.even-max-hold-seconds", 30);
        config.set("control.junction-priority", true);
        config.set("control.set-destinations", true);
        config.set("trains.rename", false);
        config.set("logging.events", false);
        tweak.accept(config);
        AtcsSettings settings = AtcsSettings.read(config);
        assertTrue(settings.warnings().isEmpty(), settings.warnings()::toString);
        Messages messages = Messages.read(bundled("messages.yml"), LOG);
        NetworkStore store = new NetworkStore(folder.resolve("network.yml"));
        store.load();
        store.edit(ignored -> network);

        Yard yard = new Yard();
        runtime = new RailwayRuntime(plugin(), store, yard, yard, (sign, powered) -> { }, settings, messages, clock);
        runtime.start(null, null, null);
        assertTrue(tasks.size() >= 7, "the runtime schedules its tasks: " + tasks.size());
    }

    /** The loop: s1 (100,4) east to s4 (400,4), then back round the north side to s1. */
    private void loopTrack() {
        route(LOOP, 0, point(100, 4), point(200, 4));
        route(LOOP, 1, point(200, 4), point(300, 4));
        route(LOOP, 2, point(300, 4), point(400, 4));
        route(LOOP, 3, point(400, 4), point(450, 4), point(450, -100), point(100, -100), point(100, 4));
    }

    @Test
    void everyControlFeatureAtOnceOnALoopWithABranchNeverHangsOrFreezes() throws Exception {
        start(network(5,
                service("5/cw", "s1:1", "s2:1", "s3:1", "s4:1").withCircular(true),
                service("a/east", "s4:1", "s5:1"),
                service("a/west", "s5:1", "s4:1")));
        // The branch shares the first 50 blocks east of s4 and leaves at a junction at (450,4).
        loopTrack();
        route(BRANCH, 0, point(400, 4), point(450, 4), point(500, 4));
        route(BACK, 0, point(500, 4), point(450, 4), point(400, 4));
        assertTrue(!runtime.railGraph().junctions().isEmpty(), "the shared stretch makes a junction: " + runtime.railGraph().nodes());

        Service loop = network.service(LOOP).orElseThrow();
        Service back = network.service(BACK).orElseThrow();
        FakeTrain l0 = new FakeTrain("L0", loop, 400, 4);
        FakeTrain a1 = new FakeTrain("A1", back, 500, 4);
        FakeTrain l1 = new FakeTrain("L1", loop, 400, 4);
        FakeTrain l2 = new FakeTrain("L2", loop, 100, 4);
        FakeTrain l3 = new FakeTrain("L3", loop, 200, 4);
        trains.addAll(List.of(l0, a1, l1, l2, l3));
        for (FakeTrain train : trains) {
            runtime.assign(train, train.service.key());
        }

        // History: L0 left s4 for s1 twenty seconds ago and is still on its way (the section
        // ahead of L1 is not clear yet); A1 left s5 five seconds ago and is crawling west towards
        // the junction that L1's next stretch passes.
        clock.set(T0.minusSeconds(40));
        arrive(l0, 3, "10s");
        clock.set(T0.minusSeconds(20));
        depart(l0);
        clock.set(T0.minusSeconds(15));
        arrive(a1, 0, "10s");
        clock.set(T0.minusSeconds(5));
        depart(a1);
        clock.set(T0.minusSeconds(8));
        arrive(l2, 0, "10s even at /1");
        clock.set(T0.minusSeconds(3));
        arrive(l1, 3, "10s block gap 30s");
        clock.set(T0.minusSeconds(1));
        arrive(l3, 1, "10s even");
        assertTrue(l1.plan != null && l2.plan != null && l3.plan != null, "the station signs stop the loop trains");

        Set<String> reasons = new LinkedHashSet<>();
        Map<String, Double> longestStand = new HashMap<>();
        int arrivals = 0;
        for (int second = 1; second <= 300; second++) {
            Instant now = T0.plusSeconds(second);
            clock.set(now);
            // A1 crawls towards the junction, which L1's next stretch passes too, and on to s4.
            // L0 is seen at s1 after two seconds: the section ahead of L1 is clear from then on,
            // and L1's headway after L0 has a few seconds left once its dwell is over.
            roll(a1, 1);
            if (atEndOfPath(a1) && a1.at == null) {
                arrive(a1, 1, "10s");
            }
            if (second == 2) {
                arrive(l0, 0, "10s even");
            }
            for (FakeTrain train : List.of(l0, l1, l2, l3)) {
                if (train.path != null) {
                    roll(train, BLOCKS_PER_SECOND);
                    if (atEndOfPath(train)) {
                        arrive(train, train.service.nextIndex(train.index), "10s even");
                        arrivals++;
                    }
                    continue;
                }
                if (train.plan == null) {
                    continue;
                }
                double stood = Duration.between(train.since, now).toMillis() / 1000.0;
                longestStand.merge(train.name, stood, Math::max);
                Optional<String> reason = runtime.holdReason(train, train.at, train.plan);
                reason.ifPresent(why -> reasons.add(why.replaceAll("[0-9]+", "N")));
                boolean dwelt = stood >= train.plan.dwellSeconds();
                boolean limit = stood >= train.plan.dwellSeconds() + train.plan.holdLimitSeconds();
                if (dwelt && (reason.isEmpty() || limit)) {
                    depart(train);
                }
            }
            for (int index : new int[] {0, 1, 2, 3, 5, 6}) {
                tasks.get(index).run();
            }
            if (second % 5 == 0) {
                for (String station : List.of("s1", "s2", "s3", "s4", "s5")) {
                    runtime.departures(station, ignored -> true);
                }
                runtime.liveJson(now);
            }
            if (second % 60 == 0) {
                tasks.get(4).run();
            }
        }
        runtime.stop();

        String seen = String.join(" | ", reasons);
        assertTrue(reasons.stream().anyMatch(why -> why.startsWith("junction")), "junction priority had its say: " + seen);
        assertTrue(reasons.stream().anyMatch(why -> why.contains("ahead has not reached")), "the section hold had its say: " + seen);
        assertTrue(reasons.stream().anyMatch(why -> why.startsWith("headway")), "the headway had its say: " + seen);
        assertTrue(reasons.stream().anyMatch(why -> why.startsWith("timetable")), "the timetable had its say: " + seen);
        assertTrue(reasons.stream().anyMatch(why -> why.startsWith("even spacing")), "even spacing had its say: " + seen);
        for (Map.Entry<String, Double> stand : longestStand.entrySet()) {
            assertTrue(stand.getValue() <= 10 + 60 + 1, stand.getKey() + " stood " + stand.getValue() + " s; the hold limit is 60 s past the dwell");
        }
        assertTrue(arrivals >= 6, "the loop trains kept going round: " + arrivals + " arrivals");
    }

    /**
     * A dispatcher's orders through the same runtime: a short turn makes the train the service
     * running the other way and sends it off backwards at the station named; a train sent to
     * the depot runs through stations, takes the depot rule at switches and is taken out of the
     * world at the depot sign; a switch fills a terminus's free platform and keeps it for the
     * train it sent there.
     */
    @Test
    void shortTurnsDepotRunsAndFreePlatformsFollowTheDispatchersOrders() throws Exception {
        start(network(5,
                service("5/cw", "s1:1", "s2:1", "s3:1", "s4:1").withCircular(true),
                service("5/ccw", "s4:1", "s3:1", "s2:1", "s1:1").withCircular(true),
                service("a/east", "s4:1", "s5:1")));
        loopTrack();
        Service loop = network.service(LOOP).orElseThrow();
        ServiceKey back = ServiceKey.parse("5/ccw");

        // Short turn: L1 at s1 is to turn back at s3.
        FakeTrain l1 = new FakeTrain("L1", loop, 100, 4);
        trains.add(l1);
        runtime.assign(l1, LOOP);
        clock.set(T0);
        arrive(l1, 0, "10s");
        assertTrue(runtime.shortTurn(l1, "s3").isEmpty(), "ordered");
        assertTrue(runtime.shortTurn(l1, "s1").isPresent(), "not where it stands");
        assertTrue(runtime.shortTurn(l1, "s5").isPresent(), "not a station of its service");
        assertTrue(runtime.shortTurns().get("L1").equals("s3"));
        clock.set(T0.plusSeconds(12));
        depart(l1);
        clock.set(T0.plusSeconds(40));
        arrive(l1, 1, "10s");
        assertTrue(l1.plan != null && l1.plan.heading() != dev.advancedtrains.atcs.control.Heading.REVERSE, "s2 is an ordinary stop");
        clock.set(T0.plusSeconds(52));
        depart(l1);
        clock.set(T0.plusSeconds(80));
        arrive(l1, 2, "10s");
        assertTrue(l1.plan != null && l1.plan.heading() == dev.advancedtrains.atcs.control.Heading.REVERSE, "at s3 the train turns back");
        assertTrue(runtime.shortTurns().isEmpty(), "the order is spent");
        var turned = runtime.journeys().stream().filter(journey -> journey.trainId().equals("L1")).findFirst().orElseThrow();
        assertTrue(back.equals(turned.service()), "now the service running the other way: " + turned.service());
        assertTrue(turned.lastIndex() == 1, "standing at s3, stop 2 of 5/ccw: " + turned.lastIndex());

        // Depot: L2 at s2 is sent to the depot; it runs through s3 and takes the depot rule.
        FakeTrain l2 = new FakeTrain("L2", loop, 200, 4);
        trains.add(l2);
        runtime.assign(l2, LOOP);
        clock.set(T0.plusSeconds(90));
        arrive(l2, 1, "10s");
        runtime.toDepot(l2);
        assertTrue(ServiceResolver.isDepotBound(l2.tags), "tagged for the depot");
        assertTrue(l2.destination.equals("depot"), "routed to the depot destination: " + l2.destination);
        assertTrue(runtime.journeys().stream().filter(journey -> journey.trainId().equals("L2")).findFirst().orElseThrow().service() == null);
        dev.advancedtrains.atcs.control.SwitchRules rules = dev.advancedtrains.atcs.control.SwitchRules.parse(
                List.of("left depot", "right free s4:1", "continue *"));
        assertTrue(runtime.switchHeading(l2, rules).orElseThrow() == dev.advancedtrains.atcs.control.Heading.LEFT, "the depot rule");
        clock.set(T0.plusSeconds(100));
        depart(l2);
        clock.set(T0.plusSeconds(130));
        arrive(l2, 2, "10s");
        assertTrue(l2.plan == null, "a depot train runs through s3");

        // Free platform: L3 heading for s4 is sent to platform 1 while it is free, which is then
        // kept for it; L4 behind gets the fallback until L3 has been and gone.
        FakeTrain l3 = new FakeTrain("L3", loop, 300, 4);
        FakeTrain l4 = new FakeTrain("L4", loop, 300, 4);
        trains.add(l3);
        trains.add(l4);
        runtime.assign(l3, LOOP);
        runtime.assign(l4, LOOP);
        clock.set(T0.plusSeconds(140));
        arrive(l3, 2, "10s");
        clock.set(T0.plusSeconds(152));
        depart(l3);
        assertTrue(runtime.switchHeading(l3, rules).orElseThrow() == dev.advancedtrains.atcs.control.Heading.RIGHT, "platform 1 is free");
        clock.set(T0.plusSeconds(155));
        arrive(l4, 2, "10s");
        clock.set(T0.plusSeconds(167));
        depart(l4);
        assertTrue(runtime.switchHeading(l4, rules).orElseThrow() == dev.advancedtrains.atcs.control.Heading.CONTINUE, "kept for L3");
        clock.set(T0.plusSeconds(180));
        arrive(l3, 3, "10s");
        assertTrue(runtime.switchHeading(l4, rules).orElseThrow() == dev.advancedtrains.atcs.control.Heading.CONTINUE, "L3 stands there");
        clock.set(T0.plusSeconds(195));
        depart(l3);
        assertTrue(runtime.switchHeading(l4, rules).orElseThrow() == dev.advancedtrains.atcs.control.Heading.RIGHT, "free again");

        // The depot sign: L2 arrives empty and is taken out of the world; an ordinary train is not.
        assertTrue(!runtime.atDepot(l3), "L3 is in service");
        assertTrue(runtime.atDepot(l2), "L2 is taken out of the world");
        assertTrue(trains.stream().noneMatch(train -> train.name.equals("L2")));
        runtime.stop();
    }

    /**
     * A short turn finds the service that runs back the way the train came, never one running its
     * own way: here the express turning at s3 becomes the southbound local, although the
     * northbound local, listed first, calls at s3 as well.
     */
    @Test
    void aShortTurnBecomesTheServiceRunningBackNeverOneRunningTheSameWay() {
        Network line = network(5,
                service("1/north", "s1:1", "s2:1", "s3:1", "s4:1", "s5:1"),
                service("1/south", "s5:1", "s4:1", "s3:1", "s2:1", "s1:1"),
                service("1/express", "s1:1", "s3:1", "s5:1"));
        Service express = line.service(ServiceKey.parse("1/express")).orElseThrow();
        assertTrue(RailwayRuntime.oppositeService(express, 1, "s3", line).map(Service::key)
                .equals(Optional.of(ServiceKey.parse("1/south"))), "the southbound local");
        Network oneWay = network(5,
                service("1/north", "s1:1", "s2:1", "s3:1", "s4:1", "s5:1"),
                service("1/express", "s1:1", "s3:1", "s5:1"));
        assertTrue(RailwayRuntime.oppositeService(oneWay.service(ServiceKey.parse("1/express")).orElseThrow(), 1, "s3", oneWay).isEmpty(),
                "no service runs back, so there is no turn");
    }

    /**
     * A short-turn order ends when the train runs through the station or is given another
     * service before it gets there, so it never turns a train round later on another trip.
     */
    @Test
    void aShortTurnOrderEndsWhenTheTrainRunsThroughOrChangesService() throws Exception {
        start(network(5,
                service("5/cw", "s1:1", "s2:1", "s3:1", "s4:1").withCircular(true),
                service("5/ccw", "s4:1", "s3:1", "s2:1", "s1:1").withCircular(true)));
        loopTrack();
        Service loop = network.service(LOOP).orElseThrow();
        FakeTrain l1 = new FakeTrain("L1", loop, 100, 4);
        trains.add(l1);
        runtime.assign(l1, LOOP);
        clock.set(T0);
        arrive(l1, 0, "10s");
        assertTrue(runtime.shortTurn(l1, "s3").isEmpty(), "ordered");
        assertTrue(runtime.skipStop(l1, "s3").isEmpty(), "and s3 skipped");
        clock.set(T0.plusSeconds(12));
        depart(l1);
        clock.set(T0.plusSeconds(40));
        arrive(l1, 1, "10s");
        clock.set(T0.plusSeconds(52));
        depart(l1);
        clock.set(T0.plusSeconds(80));
        arrive(l1, 2, "10s");
        assertTrue(l1.plan == null, "it runs through s3");
        assertTrue(runtime.shortTurns().isEmpty(), "and the order is gone with it");

        assertTrue(runtime.shortTurn(l1, "s1").isEmpty(), "ordered again");
        runtime.assign(l1, LOOP);
        assertTrue(runtime.shortTurns().containsKey("L1"), "the same service again keeps it");
        runtime.assign(l1, ServiceKey.parse("5/ccw"));
        assertTrue(runtime.shortTurns().isEmpty(), "another service ends it");
        runtime.stop();
    }

    /**
     * With set-destinations off, a train sent to the depot and put back in service gets back the
     * TrainCarts destination it had, so its switchers stop routing it to the depot. Service and
     * clear signs on the way leave a depot run alone.
     */
    @Test
    void aTrainBackFromTheDepotGetsItsOwnDestinationBack() throws Exception {
        start(network(5, service("5/cw", "s1:1", "s2:1", "s3:1", "s4:1").withCircular(true)),
                config -> config.set("control.set-destinations", false));
        Service loop = network.service(LOOP).orElseThrow();
        FakeTrain l1 = new FakeTrain("L1", loop, 100, 4);
        l1.destination = "yard-east";
        trains.add(l1);
        runtime.assign(l1, LOOP);
        runtime.toDepot(l1);
        assertTrue(l1.destination.equals("depot"), "routed to the depot: " + l1.destination);
        runtime.assign(l1, LOOP);
        assertTrue(l1.destination.equals("yard-east"), "its own destination is back: " + l1.destination);
        assertTrue(!ServiceResolver.isDepotBound(l1.tags), "and it is in service");
        runtime.stop();
    }

    /**
     * Trains left standing with nothing to move them, as a plugin reload can leave a line's
     * trains: after the stuck time one is launched and gets going; one that cannot move is taken
     * out of the world a stuck time later, unless someone is aboard; one waiting for a train
     * ahead, one a dispatcher holds and one on no service are left as they are.
     */
    @Test
    void stuckTrainsAreLaunchedAndTheOnesThatCannotMoveAreTakenAway() throws Exception {
        start(network(5, service("5/cw", "s1:1", "s2:1", "s3:1", "s4:1").withCircular(true)));
        loopTrack();
        Service loop = network.service(LOOP).orElseThrow();
        FakeTrain free = new FakeTrain("Free", loop, 150, 4);
        FakeTrain wedged = new FakeTrain("Wedged", loop, 250, 4);
        FakeTrain aboard = new FakeTrain("Aboard", loop, 350, 4);
        FakeTrain waiting = new FakeTrain("Waiting", loop, 120, 4);
        FakeTrain held = new FakeTrain("Held", loop, 180, 4);
        FakeTrain stray = new FakeTrain("Stray", loop, 300, -100);
        trains.addAll(List.of(free, wedged, aboard, waiting, held, stray));
        for (FakeTrain train : List.of(free, wedged, aboard, waiting, held)) {
            runtime.assign(train, LOOP);
        }
        free.stuck = true;
        wedged.stuck = true;
        wedged.canMove = false;
        aboard.stuck = true;
        aboard.canMove = false;
        aboard.aboard = List.of(fake(Player.class, Map.of()));
        held.stuck = true;
        runtime.holdByHand(held, "testing");
        stray.stuck = true;

        Map<String, Integer> launchedAt = new HashMap<>();
        for (int second = 1; second <= 200; second++) {
            clock.set(T0.plusSeconds(second));
            for (FakeTrain train : trains) {
                if (train.speed > 0) {
                    train.x += BLOCKS_PER_SECOND;
                }
                if (train.launches > 0) {
                    launchedAt.putIfAbsent(train.name, second);
                }
            }
            tasks.get(1).run();
            tasks.get(6).run();
        }
        runtime.stop();

        int stuckSeconds = 60;
        assertTrue(free.launches == 1 && launchedAt.get("Free") <= stuckSeconds + 7, "Free launched once after a minute: " + launchedAt);
        assertTrue(trains.contains(free), "Free got going and stays");
        assertTrue(!trains.contains(wedged), "Wedged could not move and was taken away");
        assertTrue(wedged.launches == 1, "Wedged was launched before it was taken away: " + wedged.launches);
        assertTrue(trains.contains(aboard) && aboard.launches >= 2, "Aboard has someone aboard: kept and launched again: " + aboard.launches);
        assertTrue(waiting.launches == 0 && trains.contains(waiting), "Waiting waits for something; left alone");
        assertTrue(held.launches == 0 && trains.contains(held), "Held is held by the dispatcher; left alone");
        assertTrue(stray.launches == 0 && trains.contains(stray), "Stray is on no service; left alone");
    }
}
