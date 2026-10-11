package dev.advancedtrains.atcs.board;

import com.bergerkiller.bukkit.sl.API.Variables;
import dev.advancedtrains.atcs.config.Messages;
import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Station;
import dev.advancedtrains.atcs.operation.Departures;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Publishes upcoming departures as SignLink variables, for example {@code %atcs_main_1%} (summary),
 * {@code %atcs_main_1_line%}, {@code _service}, {@code _dest}, {@code _eta} and {@code _platform}.
 * Only loaded when SignLink is installed.
 */
public final class SignLinkPublisher {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private final Map<String, String> published = new HashMap<>();

    public void publish(Network network, Messages messages, int perStation,
                        Function<String, List<Departures.Departure>> departures) {
        for (Station station : network.stations().values()) {
            if (network.servicesListing(station.id()).isEmpty()) {
                continue;
            }
            List<Departures.Departure> upcoming = departures.apply(station.id());
            String prefix = "atcs_" + station.id().replace('-', '_') + "_";
            for (int rank = 1; rank <= perStation; rank++) {
                Departures.Departure departure = rank <= upcoming.size() ? upcoming.get(rank - 1) : null;
                String base = prefix + rank;
                if (departure == null) {
                    set(base, "");
                    set(base + "_line", "");
                    set(base + "_service", "");
                    set(base + "_dest", "");
                    set(base + "_eta", "");
                    set(base + "_platform", "");
                    continue;
                }
                Line line = network.line(departure.service().key().lineId()).orElse(null);
                String lineName = line == null ? "" : line.shortName();
                String destination = departure.service().destinationFrom(departure.stopIndex())
                        .map(stop -> network.stationName(stop.stationId()))
                        .orElse(departure.service().name());
                String eta = departure.status() == Departures.Status.BOARDING ? "Boarding"
                        : departure.etaSeconds().isPresent()
                                ? PLAIN.serialize(messages.duration(departure.etaSeconds().getAsDouble()))
                                : "--";
                String platform = departure.platformId() == null ? "" : departure.platformId();
                set(base, lineName + " " + departure.service().name() + " " + destination + " " + eta);
                set(base + "_line", lineName);
                set(base + "_service", departure.service().name());
                set(base + "_dest", destination);
                set(base + "_eta", eta);
                set(base + "_platform", platform);
            }
        }
    }

    public void clear() {
        for (String name : published.keySet()) {
            Variables.get(name).set("");
        }
        published.clear();
    }

    private void set(String name, String value) {
        String previous = published.put(name, value);
        if (!value.equals(previous)) {
            Variables.get(name).set(value);
        }
    }
}
