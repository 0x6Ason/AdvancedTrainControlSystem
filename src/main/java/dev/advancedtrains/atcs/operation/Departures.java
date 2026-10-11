package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Service;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.Predicate;

/** Builds the list of upcoming departures from a station. */
public final class Departures {

    public enum Status {
        /** The train is standing at the platform. */
        BOARDING,
        /** The train is on its way. */
        EN_ROUTE
    }

    /**
     * One upcoming departure.
     *
     * @param platformId the platform the train stands at, is planned for, or was last seen using
     * @param etaSeconds seconds until the train arrives, empty when not yet known
     */
    public record Departure(
            String trainId,
            Service service,
            int stopIndex,
            @Nullable String platformId,
            OptionalDouble etaSeconds,
            Status status) {
    }

    private static final Comparator<Departure> ORDER = Comparator
            .comparingDouble((Departure departure) -> departure.etaSeconds().orElse(Double.MAX_VALUE))
            .thenComparing(Departure::trainId);

    private Departures() {
    }

    /**
     * Departures from {@code stationId}, soonest first. A train that ends its trip at the station
     * is not a departure unless it continues as another service.
     */
    public static List<Departure> from(
            String stationId,
            Predicate<String> platformFilter,
            Collection<JourneySnapshot> journeys,
            Network network,
            TravelTimes times,
            EtaCalculator eta,
            Instant now) {
        List<Departure> result = new ArrayList<>();
        for (JourneySnapshot journey : journeys) {
            if (journey.service() == null || journey.phase() == JourneyPhase.COMPLETED) {
                continue;
            }
            Service service = network.service(journey.service()).orElse(null);
            if (service == null) {
                continue;
            }
            int target = targetIndex(journey, service, stationId);
            if (target < 0) {
                continue;
            }
            // At its terminus a train departs as the service it continues as, if any.
            Service listed = service;
            int listedIndex = target;
            if (service.isTerminus(target)) {
                Service continuation = service.continuesAs() == null
                        ? null : network.service(service.continuesAs()).orElse(null);
                int start = continuation == null ? -1 : JourneyTracker.locate(continuation, stationId, -1);
                if (start < 0) {
                    continue;
                }
                listed = continuation;
                listedIndex = start;
            }
            boolean boarding = journey.phase() == JourneyPhase.AT_STOP && journey.lastIndex() == target
                    && journey.platform() != null && journey.platform().stationId().equals(stationId);
            Service finalListed = listed;
            int finalIndex = listedIndex;
            String platformId = boarding
                    ? journey.platform().platformId()
                    : listed.stop(listedIndex).platformId() != null
                            ? listed.stop(listedIndex).platformId()
                            : times.usualPlatform(service, target)
                                    .or(() -> times.usualPlatform(finalListed, finalIndex))
                                    .orElse(null);
            if (platformId != null ? !platformFilter.test(platformId) : !platformFilter.test("")) {
                continue;
            }
            OptionalDouble seconds = boarding ? OptionalDouble.of(0) : eta.secondsUntil(journey, service, target, now);
            result.add(new Departure(journey.trainId(), listed, listedIndex, platformId, seconds,
                    boarding ? Status.BOARDING : Status.EN_ROUTE));
        }
        result.sort(ORDER);
        return result;
    }

    /** The next calling stop at the station from the train's current position, or -1. */
    static int targetIndex(JourneySnapshot journey, Service service, String stationId) {
        int last = journey.lastIndex() < service.size() ? journey.lastIndex() : -1;
        if (journey.phase() == JourneyPhase.AT_STOP && last >= 0
                && service.stop(last).stationId().equals(stationId)) {
            return last;
        }
        int index = journey.phase() == JourneyPhase.IDLE ? -1 : last;
        for (int step = 0; step < service.size(); step++) {
            index = service.nextIndex(index);
            if (index < 0) {
                return -1;
            }
            if (service.stop(index).stops() && service.stop(index).stationId().equals(stationId)) {
                return index;
            }
        }
        return -1;
    }
}
