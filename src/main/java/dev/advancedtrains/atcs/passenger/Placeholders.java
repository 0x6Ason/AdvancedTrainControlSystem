package dev.advancedtrains.atcs.passenger;

import dev.advancedtrains.atcs.config.Messages;
import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Platform;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.Station;
import dev.advancedtrains.atcs.network.Transfer;
import dev.advancedtrains.atcs.operation.TrainView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.OptionalDouble;

/** Builds the MiniMessage placeholders documented in {@code messages.yml}. */
public final class Placeholders {
    private Placeholders() {
    }

    /**
     * Line and service placeholders.
     *
     * @param index the stop the train is at or departs from, which matters for the destination of a
     *              circular service
     */
    public static TagResolver.Builder service(Line line, Service service, Network network, int index) {
        return TagResolver.builder()
                .resolver(Placeholder.unparsed("line", line.name()))
                .resolver(Placeholder.unparsed("line_short", line.shortName()))
                .resolver(Placeholder.styling("line_color", TextColor.color(line.color())))
                .resolver(Placeholder.unparsed("service", service.name()))
                .resolver(Placeholder.unparsed("destination", service.destinationFrom(index)
                        .map(stop -> network.stationName(stop.stationId()))
                        .orElse(service.name())))
                .resolver(Placeholder.unparsed("suspension", line.suspension() == null ? "" : line.suspension()));
    }

    /** Placeholders for a train on a service: its line, current station, next station and ETA. */
    public static TagResolver train(TrainView view, Network network, Messages messages) {
        TagResolver.Builder builder = service(view.line(), view.service(), network, view.journey().lastIndex());
        String stationId = view.currentStationId().orElse(null);
        builder.resolver(Placeholder.unparsed("station", stationId == null ? "" : network.stationName(stationId)));
        builder.resolver(Placeholder.unparsed("platform", platformName(network, stationId,
                view.journey().platform() == null ? null : view.journey().platform().platformId())));
        String next = view.nextStationId().map(network::stationName).orElse("");
        builder.resolver(Placeholder.unparsed("next", next));
        OptionalDouble eta = view.etaNext();
        Component etaText = eta.isEmpty() ? Component.empty()
                : view.approximate() ? messages.render("passenger.eta-approximate", Placeholder.component("eta", messages.duration(eta.getAsDouble())))
                : messages.duration(eta.getAsDouble());
        builder.resolver(Placeholder.component("eta", etaText));
        Component suffix = view.late() ? messages.render("passenger.boss-bar.delayed-suffix")
                : eta.isPresent() ? messages.render("passenger.boss-bar.eta-suffix", Placeholder.component("eta", etaText))
                : Component.empty();
        builder.resolver(Placeholder.component("eta_suffix", suffix));
        return builder.build();
    }

    /** Placeholders for a station without service context (trains with no service). */
    public static TagResolver station(Network network, String stationId, @Nullable String platformId) {
        return TagResolver.builder()
                .resolver(Placeholder.unparsed("station", network.stationName(stationId)))
                .resolver(Placeholder.unparsed("platform", platformName(network, stationId, platformId)))
                .build();
    }

    /**
     * The other lines passengers can change to at a station: those calling there, then those at
     * stations a walking transfer away, each walk named once ("Line 1 (walk to Main St.)").
     */
    public static Optional<Component> interchanges(Network network, String stationId, Line current, Messages messages) {
        JoinConfiguration separator = JoinConfiguration.separator(messages.render("passenger.interchange-separator"));
        Set<String> listed = new HashSet<>();
        listed.add(current.id());
        List<Component> parts = new ArrayList<>();
        for (Line line : network.linesCallingAt(stationId)) {
            if (listed.add(line.id())) {
                parts.add(lineName(line));
            }
        }
        for (Transfer transfer : network.transfersAt(stationId)) {
            String other = transfer.other(stationId);
            List<Component> walking = new ArrayList<>();
            for (Line line : network.linesCallingAt(other)) {
                if (listed.add(line.id())) {
                    walking.add(lineName(line));
                }
            }
            if (!walking.isEmpty()) {
                parts.add(messages.render("passenger.interchange-walk",
                        Placeholder.component("lines", Component.join(separator, walking)),
                        Placeholder.unparsed("station", stationName(network, other))));
            }
        }
        return parts.isEmpty() ? Optional.empty() : Optional.of(Component.join(separator, parts));
    }

    private static Component lineName(Line line) {
        return Component.text(line.name(), TextColor.color(line.color()));
    }

    static String platformName(Network network, @Nullable String stationId, @Nullable String platformId) {
        if (stationId == null || platformId == null) {
            return "";
        }
        return network.station(stationId)
                .flatMap(station -> station.platform(platformId))
                .map(Platform::displayName)
                .orElse(platformId);
    }

    static String stationName(Network network, String stationId) {
        return network.station(stationId).map(Station::name).orElse(stationId);
    }
}
