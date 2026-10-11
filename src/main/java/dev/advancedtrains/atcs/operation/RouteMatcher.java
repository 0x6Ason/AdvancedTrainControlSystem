package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Infers which service a train runs from the stations it has been seen at. This is how trains
 * that no spawn sign, sign or command ever assigned get their service, for example the trains of
 * a loop line that run all day.
 */
public final class RouteMatcher {
    /** The most recent visits considered. */
    public static final int MAX_WINDOW = 8;

    private RouteMatcher() {
    }

    /**
     * The one service whose stop order explains the train's most recent station visits.
     *
     * <p>Starting with the last {@code minStops} visits, the window grows while more than one
     * service fits; the result is empty when no service fits or several still do. Each visit must
     * be the next stop listed by the service (calling or run-through), in the service's direction,
     * so every station of the service needs a trigger for its trains to be recognised.
     *
     * @param visits station ids in visiting order, most recent last
     */
    public static Optional<ServiceKey> infer(List<String> visits, Network network, int minStops) {
        List<String> trail = collapse(visits);
        int longest = Math.min(trail.size(), MAX_WINDOW);
        for (int window = Math.max(1, minStops); window <= longest; window++) {
            List<String> recent = trail.subList(trail.size() - window, trail.size());
            List<ServiceKey> matching = new ArrayList<>();
            for (Service service : network.services()) {
                if (fits(service, recent)) {
                    matching.add(service.key());
                }
            }
            if (matching.size() == 1) {
                return Optional.of(matching.get(0));
            }
            if (matching.isEmpty()) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Whether the stations are consecutive stops of the service, in its direction. */
    static boolean fits(Service service, List<String> stations) {
        if (service.size() == 0 || stations.isEmpty()) {
            return false;
        }
        for (int start = 0; start < service.size(); start++) {
            if (!service.stop(start).stationId().equals(stations.get(0))) {
                continue;
            }
            int index = start;
            boolean follows = true;
            for (int visit = 1; visit < stations.size() && follows; visit++) {
                index = service.nextIndex(index);
                follows = index >= 0 && service.stop(index).stationId().equals(stations.get(visit));
            }
            if (follows) {
                return true;
            }
        }
        return false;
    }

    /** Removes consecutive repeats: a train seen at two platforms of one station visited it once. */
    static List<String> collapse(List<String> visits) {
        List<String> trail = new ArrayList<>(visits.size());
        for (String station : visits) {
            if (trail.isEmpty() || !trail.get(trail.size() - 1).equals(station)) {
                trail.add(station);
            }
        }
        return trail;
    }
}
