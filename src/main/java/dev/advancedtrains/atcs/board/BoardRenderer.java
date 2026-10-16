package dev.advancedtrains.atcs.board;

import dev.advancedtrains.atcs.config.Messages;
import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.operation.Departures;
import dev.advancedtrains.atcs.passenger.Placeholders;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.List;

/** Turns departures into the four lines of a board sign using the {@code board.*} layouts. */
public final class BoardRenderer {
    /** Departures expected within this many seconds show "Arriving" instead of a countdown. */
    public static final double ARRIVING_SECONDS = 15;

    private BoardRenderer() {
    }

    public static List<Component> departure(Departures.Departure departure, Network network, Messages messages) {
        if (network.line(departure.service().key().lineId()).isEmpty()) {
            return empty(messages);
        }
        return pad(messages.renderLines("board.departure", placeholders(departure, network, messages)));
    }

    /**
     * The placeholders of one departure: {@code line}, {@code line_short}, {@code line_color},
     * {@code service}, {@code destination}, {@code platform}, {@code eta} and {@code status}.
     * Boards, hologram boards and board pictures all use them. The departure's line must exist.
     */
    public static TagResolver placeholders(Departures.Departure departure, Network network, Messages messages) {
        Line line = network.line(departure.service().key().lineId()).orElseThrow();
        TagResolver.Builder placeholders = Placeholders.service(line, departure.service(), network, departure.stopIndex());
        placeholders.resolver(Placeholder.unparsed("platform", platformName(network, departure)));
        Component eta = departure.etaSeconds().isPresent()
                ? messages.duration(departure.etaSeconds().getAsDouble())
                : Component.empty();
        placeholders.resolver(Placeholder.component("eta", eta));
        placeholders.resolver(Placeholder.component("status", status(departure, line, messages, eta)));
        return placeholders.build();
    }

    /** The status text: Boarding, Arriving, a countdown, Expected or Suspended. */
    public static Component status(Departures.Departure departure, Line line, Messages messages) {
        Component eta = departure.etaSeconds().isPresent()
                ? messages.duration(departure.etaSeconds().getAsDouble())
                : Component.empty();
        return status(departure, line, messages, eta);
    }

    public static List<Component> empty(Messages messages) {
        return pad(messages.renderLines("board.empty"));
    }

    private static Component status(Departures.Departure departure, Line line, Messages messages, Component eta) {
        if (line.suspended()) {
            return messages.render("board.status-suspended");
        }
        if (departure.status() == Departures.Status.BOARDING) {
            return messages.render("board.status-boarding");
        }
        if (departure.etaSeconds().isEmpty()) {
            return messages.render("board.status-unknown");
        }
        if (departure.etaSeconds().getAsDouble() <= ARRIVING_SECONDS) {
            return messages.render("board.status-arriving");
        }
        return messages.render("board.status-due", Placeholder.component("eta", eta));
    }

    public static String platformName(Network network, Departures.Departure departure) {
        if (departure.platformId() == null) {
            return "-";
        }
        String stationId = departure.service().stop(departure.stopIndex()).stationId();
        return network.station(stationId)
                .flatMap(station -> station.platform(departure.platformId()))
                .map(platform -> platform.displayName())
                .orElse(departure.platformId());
    }

    private static List<Component> pad(List<Component> lines) {
        Component[] result = new Component[4];
        for (int index = 0; index < 4; index++) {
            result[index] = index < lines.size() ? lines.get(index) : Component.empty();
        }
        return List.of(result);
    }
}
