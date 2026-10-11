package dev.advancedtrains.atcs.importer;

import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Platform;
import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.Station;
import dev.advancedtrains.atcs.network.Stop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds a reviewed proposal to a network without overwriting anything an operator already defined:
 * existing stations, lines and services keep their settings, and triggers already bound elsewhere are
 * skipped.
 */
public final class ProposalMerger {

    public record Result(
            Network network,
            int stationsAdded,
            int platformsAdded,
            int triggersAdded,
            int linesAdded,
            int servicesAdded,
            List<String> skipped) {
        public Result {
            skipped = List.copyOf(skipped);
        }
    }

    private ProposalMerger() {
    }

    public static Result merge(Network network, List<Station> stations) {
        return merge(network, new ProposalFile.Contents(stations, List.of()));
    }

    public static Result merge(Network network, ProposalFile.Contents proposal) {
        Network.Builder builder = network.toBuilder();
        Map<String, Station> stations = new HashMap<>(network.stations());
        Map<BlockPos, PlatformRef> bound = new HashMap<>(network.triggers());
        List<String> skipped = new ArrayList<>();
        int stationsAdded = 0;
        int platformsAdded = 0;
        int triggersAdded = 0;
        for (Station proposed : proposal.stations()) {
            boolean isNew = !stations.containsKey(proposed.id());
            Station target = isNew
                    ? new Station(proposed.id(), proposed.name(), Map.of(), proposed.location())
                    : stations.get(proposed.id());
            for (Platform platform : proposed.platforms().values()) {
                Platform existing = target.platforms().get(platform.id());
                Platform merged = existing != null ? existing : new Platform(platform.id(), platform.name(), List.of());
                int added = 0;
                for (BlockPos trigger : platform.triggers()) {
                    PlatformRef owner = bound.get(trigger);
                    if (owner != null) {
                        skipped.add(trigger.coordinates() + " is already bound to " + owner);
                        continue;
                    }
                    merged = merged.withTrigger(trigger);
                    bound.put(trigger, new PlatformRef(target.id(), platform.id()));
                    added++;
                }
                if (existing == null && (added > 0 || platform.triggers().isEmpty())) {
                    platformsAdded++;
                    target = target.withPlatform(merged);
                } else if (added > 0) {
                    target = target.withPlatform(merged);
                }
                triggersAdded += added;
            }
            if (isNew && target.platforms().isEmpty() && !proposed.platforms().isEmpty()) {
                continue;
            }
            if (isNew) {
                stationsAdded++;
            }
            stations.put(target.id(), target);
            builder.station(target);
        }

        int linesAdded = 0;
        int servicesAdded = 0;
        Map<String, Line> lines = new HashMap<>(network.lines());
        for (Line proposed : proposal.lines()) {
            Line target = lines.get(proposed.id());
            boolean isNew = target == null;
            if (isNew) {
                target = new Line(proposed.id(), proposed.name(), proposed.shortName(), proposed.color(), Map.of(),
                        proposed.suspension());
            }
            for (Service service : proposed.services().values()) {
                if (target.services().containsKey(service.key().serviceId())) {
                    skipped.add("service " + service.key() + " already exists");
                    continue;
                }
                List<String> unknown = service.stops().stream().map(Stop::stationId)
                        .filter(station -> !stations.containsKey(station)).distinct().toList();
                if (!unknown.isEmpty()) {
                    skipped.add("service " + service.key() + " names unknown station(s) " + String.join(", ", unknown));
                    continue;
                }
                target = target.withService(service);
                servicesAdded++;
            }
            if (isNew && target.services().isEmpty() && !proposed.services().isEmpty()) {
                continue;
            }
            if (isNew) {
                linesAdded++;
            }
            lines.put(target.id(), target);
            builder.line(target);
        }
        return new Result(builder.build(), stationsAdded, platformsAdded, triggersAdded, linesAdded, servicesAdded, skipped);
    }
}
