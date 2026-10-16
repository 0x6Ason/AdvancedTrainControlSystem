package dev.advancedtrains.atcs.command;

import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.network.Station;
import dev.advancedtrains.atcs.operation.Departures;
import dev.advancedtrains.atcs.operation.JourneySnapshot;
import dev.advancedtrains.atcs.operation.ServiceResolver;
import dev.advancedtrains.atcs.operation.TrainInfo;
import dev.advancedtrains.atcs.operation.TrainView;
import dev.advancedtrains.atcs.runtime.TrainHandle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static dev.advancedtrains.atcs.command.Node.group;
import static dev.advancedtrains.atcs.command.Node.leaf;
import static dev.advancedtrains.atcs.command.Support.MANAGE;
import static dev.advancedtrains.atcs.command.Support.USE;

/** {@code /atcs train ...}, {@code /atcs trains} and {@code /atcs departures}. */
final class TrainCommands {
    private TrainCommands() {
    }

    static Node train(Support support) {
        Node.Completer trains = (context, index) -> index == 0 ? support.trainNames() : List.of();
        return group("train", USE, "Trains and their services",
                leaf("info", USE, "[train]", "Show the train you ride or look at", context -> info(support, context), trains),
                leaf("assign", MANAGE, "<line/service> [train]", "Run a train as a service", context -> {
                    ServiceKey key = support.service(context.arg(0)).key();
                    TrainHandle train = support.train(context, 1);
                    support.runtime.assign(train, key);
                    Chat.success(context.sender(), "Train " + train.name() + " now runs " + key);
                }, (context, index) -> index == 0 ? support.serviceKeys() : index == 1 ? support.trainNames() : List.of()),
                leaf("unassign", MANAGE, "[train]", "Take a train out of service and off automatic assignment", context -> {
                    TrainHandle train = support.train(context, 0);
                    support.runtime.unassign(train, true);
                    Chat.success(context.sender(), "Train " + train.name()
                            + " has no service and will not be assigned automatically. /atcs train auto puts it back.");
                }, trains),
                leaf("auto", MANAGE, "[train]", "Let spawn signs, match rules and its route assign a train again", context -> {
                    TrainHandle train = support.train(context, 0);
                    support.runtime.unassign(train, false);
                    Chat.success(context.sender(), "Train " + train.name() + " is assigned automatically again");
                }, trains),
                leaf("hold", MANAGE, "<train> [reason...]", "Keep a train at its current or next station sign until released", context -> {
                    TrainHandle train = support.train(context, 0);
                    String reason = context.restOr(1, "");
                    support.runtime.holdByHand(train, reason);
                    Chat.success(context.sender(), "Train " + train.name() + " is held at its "
                            + (support.runtime.control().standingAt(train.name()).isPresent() ? "platform" : "next station sign")
                            + (reason.isEmpty() ? "" : " (" + reason + ")") + " until /atcs train release " + train.name());
                }, trains),
                leaf("skip", MANAGE, "<train> <station>", "Make a train run through a station it would call at, once; riders are told", context -> {
                    TrainHandle train = support.train(context, 0);
                    Station station = support.station(context.arg(1));
                    java.util.Optional<String> problem = support.runtime.skipStop(train, station.id());
                    if (problem.isPresent()) {
                        throw new CommandError(problem.get());
                    }
                    Chat.success(context.sender(), "Train " + train.name() + " will run through " + station.name() + " next time");
                }, (context, index) -> index == 0 ? support.trainNames() : index == 1 ? support.stationIds() : List.of()),
                leaf("release", MANAGE, "<train>", "Let a held train go", context -> {
                    TrainHandle train = support.train(context, 0);
                    if (support.runtime.releaseByHand(train)) {
                        Chat.success(context.sender(), "Train " + train.name() + " may go");
                    } else {
                        Chat.info(context.sender(), "Train " + train.name() + " was not held by hand");
                    }
                }, trains));
    }

    static Node trains(Support support) {
        return leaf("trains", MANAGE, "", "List loaded trains and what they run", context -> list(support, context));
    }

    static Node departures(Support support) {
        return leaf("departures", USE, "<station> [platform]", "Show the next departures from a station",
                context -> departures(support, context), (context, index) -> switch (index) {
                    case 0 -> support.stationIds();
                    case 1 -> support.platformIds(context.argOr(0, ""));
                    default -> List.of();
                });
    }

    private static void info(Support support, CommandContext context) throws CommandError {
        TrainHandle train = support.train(context, 0);
        Network network = support.network();
        CommandSender sender = context.sender();
        TrainInfo info = train.info();
        sender.sendMessage(Chat.header("Train " + train.name()));
        Optional<TrainView> view = support.runtime.view(train.name());
        Optional<JourneySnapshot> journey = support.runtime.journey(train.name());
        if (view.isPresent()) {
            TrainView value = view.get();
            sender.sendMessage(Chat.field("Service", Component.text()
                    .append(Component.text(value.line().name() + " " + value.service().name(), TextColor.color(value.line().color())))
                    .append(Component.text(" (" + value.service().key() + ")", NamedTextColor.DARK_GRAY)).build()));
            sender.sendMessage(Chat.field("Destination", value.destinationName(network)));
            value.currentStationId().ifPresent(station -> sender.sendMessage(Chat.field("At", network.stationName(station))));
            value.nextStationId().ifPresent(next -> sender.sendMessage(Chat.field("Next", network.stationName(next)
                    + (value.etaNext().isPresent() ? String.format(Locale.ROOT, " in about %.0fs", value.etaNext().getAsDouble()) : ""))));
            sender.sendMessage(Chat.field("Progress", Math.round(value.progress() * 100) + "% ("
                    + value.journey().phase().name().toLowerCase(Locale.ROOT).replace('_', ' ') + ")"));
        } else {
            sender.sendMessage(Chat.field("Service", "none"));
            journey.map(JourneySnapshot::currentStationId).ifPresent(station ->
                    sender.sendMessage(Chat.field("At", network.stationName(station))));
        }
        String assignedBy;
        if (ServiceResolver.isManual(info.tags())) {
            assignedBy = "nobody: taken out of service with /atcs train unassign";
        } else if (view.isEmpty()) {
            assignedBy = "-";
        } else if (ServiceResolver.isInferred(info.tags())) {
            assignedBy = "its route (inferred from the stations it visited)";
        } else if (ServiceResolver.taggedService(info.tags()).isPresent()) {
            assignedBy = "ATCS sign, spawn sign or command";
        } else {
            assignedBy = "match rule";
        }
        sender.sendMessage(Chat.field("Assigned by", assignedBy));
        if (!info.destination().isEmpty()) {
            sender.sendMessage(Chat.field("TrainCarts destination", info.destination()));
        }
        if (!info.destinationRoute().isEmpty()) {
            sender.sendMessage(Chat.field("TrainCarts route", String.join(" > ", info.destinationRoute())));
        }
        if (!info.tags().isEmpty()) {
            sender.sendMessage(Chat.field("Tags", String.join(", ", info.tags())));
        }
        sender.sendMessage(Chat.field("Riders", Integer.toString(train.riders().size())));
    }

    private static void list(Support support, CommandContext context) {
        Network network = support.network();
        List<TrainHandle> trains = List.copyOf(support.trains.loadedTrains());
        if (trains.isEmpty()) {
            Chat.info(context.sender(), "No trains are loaded");
            return;
        }
        context.sender().sendMessage(Chat.header("Loaded trains (" + trains.size() + ")"));
        for (TrainHandle train : trains) {
            TextComponent.Builder line = Component.text().append(Chat.run(train.name(), "/atcs train info " + train.name()));
            Optional<TrainView> view = support.runtime.view(train.name());
            if (view.isPresent()) {
                TrainView value = view.get();
                line.append(Component.text(" " + value.line().shortName() + " " + value.service().name(),
                        TextColor.color(value.line().color())));
                line.append(Component.text(" to " + value.destinationName(network), NamedTextColor.WHITE));
                String where = value.currentStationId().map(station -> " at " + network.stationName(station))
                        .orElse(value.nextStationId().map(next -> " next " + network.stationName(next)).orElse(""));
                line.append(Component.text(where, NamedTextColor.GRAY));
            } else {
                line.append(Component.text(" no service", NamedTextColor.DARK_GRAY));
            }
            context.sender().sendMessage(line.build());
        }
    }

    private static void departures(Support support, CommandContext context) throws CommandError {
        Station station = support.station(context.arg(0));
        String platform = context.size() > 1 ? Support.id("platform", context.arg(1)) : null;
        List<Departures.Departure> departures = support.runtime.departures(station.id(),
                id -> platform == null || platform.equals(id));
        Network network = support.network();
        context.sender().sendMessage(Chat.header("Departures from " + station.name()
                + (platform == null ? "" : " platform " + platform)));
        if (departures.isEmpty()) {
            Chat.info(context.sender(), "No trains are on their way");
            return;
        }
        for (Departures.Departure departure : departures.stream().limit(8).toList()) {
            Line line = network.line(departure.service().key().lineId()).orElseThrow();
            String destination = departure.service().destinationFrom(departure.stopIndex())
                    .map(stop -> network.stationName(stop.stationId())).orElse(departure.service().name());
            String when = departure.status() == Departures.Status.BOARDING ? "boarding"
                    : departure.etaSeconds().isPresent() ? formatSeconds(departure.etaSeconds().getAsDouble()) : "expected";
            context.sender().sendMessage(Component.text()
                    .append(Component.text(line.shortName() + " " + departure.service().name(), TextColor.color(line.color())))
                    .append(Component.text(" to " + destination, NamedTextColor.WHITE))
                    .append(Component.text(departure.platformId() == null ? "" : "  platform " + departure.platformId(), NamedTextColor.GRAY))
                    .append(Component.text("  " + when, line.suspended() ? NamedTextColor.RED : NamedTextColor.AQUA))
                    .build());
        }
    }

    static String formatSeconds(double seconds) {
        long total = Math.max(0, Math.round(seconds));
        return total < 60 ? total + "s" : String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60);
    }
}
