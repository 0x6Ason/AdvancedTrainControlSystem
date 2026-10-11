package dev.advancedtrains.atcs.runtime;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides when a service with a fleet needs another train: when fewer than the configured number
 * exist and the spacing interval has passed since the last spawn attempt.
 */
public final class FleetManager {
    private final Map<ServiceKey, Instant> lastAttempt = new HashMap<>();

    /** Services that are short of trains and allowed to spawn now. */
    public List<Service> due(Network network, Map<ServiceKey, Integer> trainsInService, Instant now) {
        List<Service> due = new ArrayList<>();
        for (Service service : network.services()) {
            if (service.fleet() == null) {
                continue;
            }
            if (trainsInService.getOrDefault(service.key(), 0) >= service.fleet().trains()) {
                continue;
            }
            Instant last = lastAttempt.get(service.key());
            if (last != null && last.plus(service.fleet().every()).isAfter(now)) {
                continue;
            }
            due.add(service);
        }
        return due;
    }

    /** Records a spawn, or a failed attempt, so the next one waits for the interval. */
    public void attempted(ServiceKey service, Instant now) {
        lastAttempt.put(service, now);
    }

    public void forget(ServiceKey service) {
        lastAttempt.remove(service);
    }
}
