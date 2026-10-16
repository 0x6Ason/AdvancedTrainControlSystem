package dev.advancedtrains.atcs.network;

import dev.advancedtrains.atcs.fare.FareTable;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Looks the network over for what will not work or not show: platforms no train can be seen at,
 * stations no service calls at, boards whose signs are gone, fares that name nothing. Each finding
 * says what to do. Pure: the runtime passes in what it knows about the world.
 */
public final class NetworkLint {

    public enum Level { PROBLEM, WARNING, NOTE }

    public record Finding(Level level, String text) {
    }

    /**
     * What only the running server knows.
     *
     * @param observedTriggers  positions of ATCS stop and station signs seen per station
     * @param signExists        whether a sign still stands at a position (null: unknown)
     * @param learnedSegments   how many segments of a service have a learned time
     * @param fares             the fare table, or null when fares are not in use
     * @param routeExists       whether any route joins two stations (null: not checked)
     */
    public record Inputs(
            Map<String, Set<BlockPos>> observedTriggers,
            @Nullable Predicate<BlockPos> signExists,
            ToIntFunction<Service> learnedSegments,
            @Nullable FareTable fares,
            @Nullable BiPredicate<String, String> routeExists) {

        public static Inputs none() {
            return new Inputs(Map.of(), null, service -> 1, null, null);
        }
    }

    private NetworkLint() {
    }

    public static List<Finding> check(Network network, Inputs inputs) {
        List<Finding> findings = new ArrayList<>();
        for (String issue : network.issues()) {
            findings.add(new Finding(Level.PROBLEM, issue));
        }
        for (Station station : network.stations().values()) {
            Set<BlockPos> observed = inputs.observedTriggers().getOrDefault(station.id(), Set.of());
            boolean anyTrigger = observed.isEmpty() && station.platforms().values().stream().allMatch(platform -> platform.triggers().isEmpty());
            if (station.platforms().isEmpty() && observed.isEmpty()) {
                findings.add(new Finding(Level.WARNING, station.name() + " has no platform, so no train is ever seen there:"
                        + " place an atcs station sign, or bind its TrainCarts station sign with /atcs station bind " + station.id()));
            } else if (anyTrigger) {
                findings.add(new Finding(Level.WARNING, station.name() + " has platforms but no trigger sign on any of them"));
            } else {
                for (Platform platform : station.platforms().values()) {
                    if (platform.triggers().isEmpty() && observed.isEmpty()) {
                        findings.add(new Finding(Level.NOTE, station.name() + " platform " + platform.id()
                                + " has no trigger sign; trains using it are reported at another platform or not at all"));
                    }
                }
            }
            if (network.linesCallingAt(station.id()).isEmpty()) {
                findings.add(new Finding(Level.NOTE, "No service calls at " + station.name()
                        + "; add it to a service with /atcs line addstop or /atcs service addstop"));
            }
            if (station.markerPosition().isEmpty() && observed.isEmpty()) {
                findings.add(new Finding(Level.NOTE, station.name() + " has no position, so maps cannot place it:"
                        + " stand there and run /atcs station locate " + station.id()));
            }
        }
        for (Line line : network.lines().values()) {
            if (line.services().isEmpty()) {
                findings.add(new Finding(Level.WARNING, line.name() + " has no services: /atcs service create " + line.id() + "/<service>"));
            }
            for (Service service : line.services().values()) {
                for (int index = 0; index + 1 < service.size(); index++) {
                    if (service.stop(index).stationId().equals(service.stop(index + 1).stationId())) {
                        findings.add(new Finding(Level.WARNING, service.key() + " lists " + network.stationName(service.stop(index).stationId())
                                + " twice in a row (stops " + (index + 1) + " and " + (index + 2) + ")"));
                    }
                }
                if (service.circular() && service.continuesAs() != null) {
                    findings.add(new Finding(Level.NOTE, service.key() + " is circular, so it never ends and never continues as " + service.continuesAs()));
                }
                if (service.size() >= 2 && inputs.learnedSegments().applyAsInt(service) == 0) {
                    findings.add(new Finding(Level.NOTE, "No train has been seen running " + service.key()
                            + " yet; estimates for it are defaults until one does"));
                }
            }
        }
        for (Transfer transfer : network.transfers()) {
            for (String id : List.of(transfer.a(), transfer.b())) {
                Station station = network.station(id).orElse(null);
                if (station == null) {
                    findings.add(new Finding(Level.PROBLEM, "Walking transfer " + transfer.a() + " to " + transfer.b() + " names unknown station '" + id + "'"));
                } else if (transfer.walk() == null && station.markerPosition().isEmpty()
                        && inputs.observedTriggers().getOrDefault(id, Set.of()).isEmpty()) {
                    findings.add(new Finding(Level.WARNING, "The walk between " + transfer.a() + " and " + transfer.b()
                            + " is estimated from positions, but " + station.name() + " has none: give it a time with /atcs transfer add "
                            + transfer.a() + " " + transfer.b() + " <seconds>"));
                }
            }
        }
        if (inputs.signExists() != null) {
            for (Board board : network.boards().values()) {
                for (BlockPos sign : board.signs()) {
                    if (!inputs.signExists().test(sign)) {
                        findings.add(new Finding(Level.PROBLEM, "Board " + board.id() + ": the sign at " + sign.coordinates()
                                + " is gone; /atcs board unbind it or place a sign there"));
                    }
                }
            }
        }
        FareTable fares = inputs.fares();
        if (fares != null && fares.configured()) {
            for (String key : fares.pairs().keySet()) {
                for (String id : FareTable.stationsOf(key)) {
                    if (network.station(id).isEmpty()) {
                        findings.add(new Finding(Level.WARNING, "fares.yml names unknown station '" + id + "' in pair " + key));
                    }
                }
            }
            fares.zones().keySet().stream().filter(id -> network.station(id).isEmpty()).forEach(id ->
                    findings.add(new Finding(Level.WARNING, "fares.yml puts unknown station '" + id + "' in a zone")));
            if (!fares.zoneFares().isEmpty()) {
                for (Station station : network.stations().values()) {
                    if (fares.zone(station.id()).isEmpty()) {
                        findings.add(new Finding(Level.NOTE, station.name() + " is in no fare zone: /atcs fare zone " + station.id() + " <zone>"));
                    }
                }
            }
            if (inputs.routeExists() != null) {
                Set<String> unreachable = new HashSet<>();
                List<Station> stations = new ArrayList<>(network.stations().values());
                for (int first = 0; first < stations.size(); first++) {
                    for (int second = first + 1; second < stations.size(); second++) {
                        String a = stations.get(first).id();
                        String b = stations.get(second).id();
                        if (fares.pair(a, b).isEmpty() && fares.zoneFare(a, b).isEmpty() && !inputs.routeExists().test(a, b)) {
                            unreachable.add(network.stationName(a) + " and " + network.stationName(b));
                        }
                    }
                }
                if (!unreachable.isEmpty()) {
                    findings.add(new Finding(Level.WARNING, unreachable.size() + " pair(s) of stations have no route, so the formula charges"
                            + " only the flat fare between them, for example " + unreachable.iterator().next()));
                }
            }
        }
        return findings;
    }
}
