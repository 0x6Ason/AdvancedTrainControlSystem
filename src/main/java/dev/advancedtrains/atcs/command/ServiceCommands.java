package dev.advancedtrains.atcs.command;

import dev.advancedtrains.atcs.importer.TrainSign;
import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Fleet;
import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.network.ServiceMatch;
import dev.advancedtrains.atcs.network.Station;
import dev.advancedtrains.atcs.network.Stop;
import dev.advancedtrains.atcs.runtime.ServiceRecorder;
import dev.advancedtrains.atcs.runtime.TrainHandle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Rail;
import org.bukkit.block.sign.Side;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static dev.advancedtrains.atcs.command.Node.group;
import static dev.advancedtrains.atcs.command.Node.leaf;
import static dev.advancedtrains.atcs.command.Support.MANAGE;
import static dev.advancedtrains.atcs.command.Support.USE;

/** {@code /atcs service ...} */
final class ServiceCommands {
    private ServiceCommands() {
    }

    static Node build(Support support) {
        Node.Completer services = (context, index) -> index == 0 ? support.serviceKeys() : List.of();
        return group("service", USE, "Services: the stopping patterns trains run",
                leaf("info", USE, "<line/service>", "Show a service's stops and what has been learned",
                        context -> info(support, context), services),
                leaf("create", MANAGE, "<line/service> [name...]", "Create a service, e.g. /atcs service create a/local-north Local",
                        context -> create(support, context), (context, index) -> index == 0
                                ? support.lineIds().stream().map(line -> line + "/").toList() : List.of()),
                leaf("stops", MANAGE, "<line/service> <stop...>", "Set the stops: station, station:platform, ~station to run through",
                        context -> stops(support, context), (context, index) -> index == 0 ? support.serviceKeys()
                                : stopSuggestions(support, context.argOr(index, ""))),
                leaf("record", MANAGE, "<line/service>|stop|cancel", "Record stops by riding a train along the route",
                        context -> record(support, context), (context, index) -> {
                            if (index != 0) {
                                return List.of();
                            }
                            List<String> options = new ArrayList<>(support.serviceKeys());
                            options.add("stop");
                            options.add("cancel");
                            return options;
                        }),
                leaf("spawn", MANAGE, "<line/service>", "New trains from the TrainCarts spawn sign you look at run this service",
                        context -> spawn(support, context), services),
                leaf("unspawn", MANAGE, "", "Stop assigning new trains from the spawn sign you look at",
                        context -> unspawn(support, context)),
                leaf("fleet", MANAGE, "<line/service> <trains> <every> <train>|off",
                        "Let ATCS keep trains running on a service without spawn signs; stand on the rail facing their way",
                        context -> fleet(support, context), (context, index) -> switch (index) {
                            case 0 -> support.serviceKeys();
                            case 1 -> List.of("1", "2", "3", "off");
                            case 2 -> List.of("00:35", "01:00", "02:00");
                            case 3 -> List.of("4m", "6m", "9m");
                            default -> List.of();
                        }),
                leaf("reverse", MANAGE, "<line/service> <new-service> [name...]", "Create the opposite direction of a service",
                        context -> reverse(support, context), services),
                leaf("rename", MANAGE, "<line/service> <name...>", "Change the passenger-facing service name", context -> {
                    Service service = support.service(context.arg(0));
                    String name = context.rest(1);
                    save(support, service.withName(name));
                    Chat.success(context.sender(), service.key() + " is now called " + name);
                }, services),
                leaf("circular", MANAGE, "<line/service> <true|false>", "Whether the last stop is followed by the first",
                        context -> {
                            Service service = support.service(context.arg(0));
                            boolean circular = Boolean.parseBoolean(context.arg(1));
                            save(support, service.withCircular(circular));
                            Chat.success(context.sender(), service.key() + (circular ? " is circular" : " is not circular"));
                        }, (context, index) -> index == 0 ? support.serviceKeys() : List.of("true", "false")),
                leaf("continues", MANAGE, "<line/service> <line/service|none>", "What trains become after the last stop",
                        context -> continues(support, context), (context, index) -> {
                            if (index > 1) {
                                return List.of();
                            }
                            List<String> options = new ArrayList<>(support.serviceKeys());
                            if (index == 1) {
                                options.add("none");
                            }
                            return options;
                        }),
                leaf("match", MANAGE, "<line/service> [destination|tag|train-name <value>|clear]",
                        "Assign trains automatically by TrainCarts destination, tag or name",
                        context -> match(support, context), (context, index) -> switch (index) {
                            case 0 -> support.serviceKeys();
                            case 1 -> List.of("destination", "tag", "train-name", "clear");
                            default -> List.of();
                        }),
                leaf("remove", MANAGE, "<line/service>", "Remove a service", context -> {
                    Service service = support.service(context.arg(0));
                    support.edit(network -> network.toBuilder().line(network.line(service.key().lineId()).orElseThrow()
                            .withoutService(service.key().serviceId())).build());
                    support.runtime.times().forget(service.key());
                    support.runtime.clearPaths(service.key());
                    Chat.success(context.sender(), "Removed service " + service.key());
                }, services),
                leaf("resetpaths", MANAGE, "<line/service>", "Forget the recorded track paths drawn on maps", context -> {
                    Service service = support.service(context.arg(0));
                    support.runtime.clearPaths(service.key());
                    Chat.success(context.sender(), "Track paths of " + service.key() + " will be recorded again by the next trains");
                }, services));
    }

    private static void save(Support support, Service service) throws CommandError {
        support.edit(network -> network.toBuilder()
                .line(network.line(service.key().lineId()).orElseThrow().withService(service)).build());
    }

    private static List<String> stopSuggestions(Support support, String typed) {
        String prefix = typed.startsWith("~") ? "~" : "";
        String body = typed.substring(prefix.length());
        int colon = body.indexOf(':');
        if (colon >= 0) {
            String stationId = body.substring(0, colon);
            return support.platformIds(stationId).stream().map(platform -> prefix + stationId + ":" + platform).toList();
        }
        return support.stationIds().stream().map(id -> prefix + id).toList();
    }

    private static void info(Support support, CommandContext context) throws CommandError {
        Service service = support.service(context.arg(0));
        Network network = support.network();
        Line line = network.line(service.key().lineId()).orElseThrow();
        CommandSender sender = context.sender();
        sender.sendMessage(Chat.header(line.name() + " " + service.name()));
        sender.sendMessage(Chat.field("Id", service.key().toString()));
        sender.sendMessage(Chat.field("Destination", service.destination()
                .map(stop -> network.stationName(stop.stationId())).orElse(service.circular() ? "circular" : "-")));
        for (int index = 0; index < service.size(); index++) {
            Stop stop = service.stop(index);
            String platform = stop.platformId() == null ? ""
                    : " platform " + stop.platformId();
            String timing = support.runtime.times().segmentSeconds(service, index).isPresent()
                    && service.nextIndex(index) >= 0
                    ? String.format(Locale.ROOT, "  %.0fs to next", support.runtime.times().segmentSeconds(service, index).getAsDouble())
                    : "";
            sender.sendMessage(Component.text()
                    .append(Component.text((index + 1) + ". ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(network.stationName(stop.stationId()), stop.pass() ? NamedTextColor.GRAY : NamedTextColor.WHITE))
                    .append(Component.text(platform + (stop.pass() ? " (runs through)" : ""), NamedTextColor.GRAY))
                    .append(Component.text(timing, NamedTextColor.DARK_AQUA))
                    .build());
        }
        if (service.stops().isEmpty()) {
            sender.sendMessage(Component.text().append(Component.text("No stops yet. ", NamedTextColor.GRAY))
                    .append(Chat.suggest("Set stops", "/atcs service stops " + service.key() + " "))
                    .append(Component.text(" or ride a train and ", NamedTextColor.GRAY))
                    .append(Chat.suggest("record them", "/atcs service record " + service.key()))
                    .build());
        }
        if (service.continuesAs() != null) {
            sender.sendMessage(Chat.field("Continues as", service.continuesAs().toString()));
        }
        if (!service.spawnSigns().isEmpty()) {
            sender.sendMessage(Chat.field("Spawn signs", String.join(", ",
                    service.spawnSigns().stream().map(BlockPos::coordinates).toList())));
        }
        if (service.fleet() != null) {
            sender.sendMessage(Chat.field("Fleet", service.fleet().describe()));
        }
        ServiceMatch match = service.match();
        if (!match.isEmpty()) {
            List<String> rules = new ArrayList<>();
            match.destinations().forEach(value -> rules.add("destination " + value));
            match.tags().forEach(value -> rules.add("tag " + value));
            match.trainNames().forEach(value -> rules.add("train name " + value));
            sender.sendMessage(Chat.field("Assigns trains with", String.join(", ", rules)));
        }
        int segments = Math.max(0, service.circular() ? service.size() : service.size() - 1);
        sender.sendMessage(Chat.field("Learned travel times", support.runtime.times().learnedSegments(service) + "/" + segments));
        sender.sendMessage(Chat.field("Recorded track paths", support.runtime.paths().recordedSegments(service) + "/" + segments));
        long running = support.runtime.journeys().stream()
                .filter(journey -> service.key().equals(journey.service())).count();
        sender.sendMessage(Chat.field("Trains on this service", Long.toString(running)));
    }

    private static void create(Support support, CommandContext context) throws CommandError {
        ServiceKey key = support.serviceKey(context.arg(0));
        Line line = support.line(key.lineId());
        if (line.service(key.serviceId()).isPresent()) {
            throw new CommandError("Service " + key + " already exists");
        }
        String name = context.restOr(1, defaultName(key.serviceId()));
        support.edit(network -> network.toBuilder()
                .line(line.withService(Service.of(key, name, List.of()))).build());
        context.sender().sendMessage(Component.text()
                .append(Component.text("ATCS ", NamedTextColor.GOLD))
                .append(Component.text("Created " + key + " (" + name + "). Next: ", NamedTextColor.GREEN))
                .append(Chat.suggest("set its stops", "/atcs service stops " + key + " "))
                .append(Component.text(" or ", NamedTextColor.GREEN))
                .append(Chat.suggest("record them on a train", "/atcs service record " + key))
                .build());
    }

    private static String defaultName(String serviceId) {
        String words = serviceId.replace('-', ' ').replace('_', ' ');
        return words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
    }

    private static void stops(Support support, CommandContext context) throws CommandError {
        Service service = support.service(context.arg(0));
        if (context.size() < 2) {
            throw new CommandError("List the stops, e.g. /atcs service stops " + service.key() + " main nerdy:2 ~golden-beach new-village");
        }
        Network network = support.network();
        List<Stop> stops = new ArrayList<>();
        for (String text : List.of(context.args()).subList(1, context.size())) {
            Stop stop;
            try {
                stop = Stop.parse(text);
            } catch (IllegalArgumentException exception) {
                throw new CommandError("'" + text + "': " + exception.getMessage());
            }
            Station station = network.station(stop.stationId())
                    .orElseThrow(() -> new CommandError("Unknown station '" + stop.stationId() + "'"));
            if (stop.platformId() != null && station.platform(stop.platformId()).isEmpty()) {
                throw new CommandError(station.name() + " has no platform '" + stop.platformId() + "'");
            }
            stops.add(stop);
        }
        save(support, service.withStops(stops));
        Chat.success(context.sender(), service.key() + " now has " + stops.size() + " stops: "
                + String.join(" > ", stops.stream().map(stop -> network.stationName(stop.stationId())).toList()));
    }

    private static void record(Support support, CommandContext context) throws CommandError {
        Player player = context.player();
        ServiceRecorder recorder = support.runtime.recorder();
        String argument = context.arg(0).toLowerCase(Locale.ROOT);
        if (argument.equals("cancel")) {
            recorder.finish(player).orElseThrow(() -> new CommandError("You are not recording a service"));
            Chat.info(player, "Recording cancelled");
            return;
        }
        if (argument.equals("stop")) {
            ServiceRecorder.Session session = recorder.finish(player)
                    .orElseThrow(() -> new CommandError("You are not recording a service"));
            if (session.stops().size() < 2) {
                throw new CommandError("Only " + session.stops().size() + " stop(s) were recorded; nothing saved");
            }
            Line line = support.line(session.service().lineId());
            Service existing = line.service(session.service().serviceId()).orElse(null);
            Service updated = existing == null
                    ? Service.of(session.service(), defaultName(session.service().serviceId()), session.stops())
                    : existing.withStops(session.stops());
            support.edit(network -> network.toBuilder()
                    .line(network.line(line.id()).orElseThrow().withService(updated)).build());
            support.trains.train(session.trainName()).ifPresent(train -> support.runtime.assign(train, session.service()));
            Chat.success(player, "Saved " + session.stops().size() + " stops to " + session.service()
                    + ". Check them with /atcs service info " + session.service());
            return;
        }
        ServiceKey key = support.serviceKey(context.arg(0));
        support.line(key.lineId());
        TrainHandle train = support.train(context, 1);
        recorder.start(player, train.name(), key);
        Chat.success(player, "Recording stops of train " + train.name() + " for " + key
                + ". Ride to the end of the route, then run /atcs service record stop.");
    }

    private static void spawn(Support support, CommandContext context) throws CommandError {
        Service service = support.service(context.arg(0));
        Block block = Support.targetSign(context.player());
        if (!isSpawnSign(block)) {
            throw new CommandError("That is not a TrainCarts spawn sign");
        }
        BlockPos position = Support.position(block);
        support.edit(network -> {
            Network.Builder builder = network.toBuilder();
            for (Line line : network.lines().values()) {
                Line updated = line;
                for (Service other : line.services().values()) {
                    List<BlockPos> signs = new ArrayList<>(other.spawnSigns());
                    boolean changed = signs.remove(position);
                    if (other.key().equals(service.key())) {
                        signs.add(position);
                        changed = true;
                    }
                    if (changed) {
                        updated = updated.withService(other.withSpawnSigns(signs));
                    }
                }
                builder.line(updated);
            }
            return builder.build();
        });
        Chat.success(context.sender(), "New trains from the spawn sign at " + position.coordinates() + " run " + service.key());
    }

    private static void unspawn(Support support, CommandContext context) throws CommandError {
        BlockPos position = Support.position(Support.targetSign(context.player()));
        Service service = support.network().serviceSpawnedAt(position)
                .flatMap(key -> support.network().service(key))
                .orElseThrow(() -> new CommandError("No service assigns trains from that sign"));
        List<BlockPos> signs = new ArrayList<>(service.spawnSigns());
        signs.remove(position);
        save(support, service.withSpawnSigns(signs));
        Chat.success(context.sender(), "Trains from that spawn sign are no longer assigned to " + service.key());
    }

    private static void fleet(Support support, CommandContext context) throws CommandError {
        Service service = support.service(context.arg(0));
        if (context.arg(1).equalsIgnoreCase("off")) {
            save(support, service.withFleet(null));
            Chat.success(context.sender(), service.key() + " no longer keeps a fleet; its trains stay as they are");
            return;
        }
        Player player = context.player();
        int trains;
        try {
            trains = Integer.parseInt(context.arg(1));
        } catch (NumberFormatException exception) {
            throw new CommandError("'" + context.arg(1) + "' is not a number of trains");
        }
        Duration every;
        try {
            every = Fleet.parseEvery(context.arg(2));
        } catch (IllegalArgumentException exception) {
            throw new CommandError(exception.getMessage());
        }
        String pattern = context.arg(3);
        BlockPos spawn = Support.position(railUnder(player));
        Fleet.Direction direction = Fleet.Direction.fromYaw(player.getLocation().getYaw());
        Fleet fleet;
        try {
            fleet = new Fleet(trains, every, spawn, direction, pattern);
        } catch (IllegalArgumentException exception) {
            throw new CommandError(exception.getMessage());
        }
        save(support, service.withFleet(fleet));
        Chat.success(context.sender(), service.key() + " keeps " + fleet.describe() + ". A train appears every "
                + Fleet.formatEvery(every) + " while fewer than " + trains + " are in service.");
    }

    private static Block railUnder(Player player) throws CommandError {
        Block at = player.getLocation().getBlock();
        for (Block candidate : List.of(at, at.getRelative(0, -1, 0))) {
            if (candidate.getBlockData() instanceof Rail) {
                return candidate;
            }
        }
        throw new CommandError("Stand on the rail where trains should appear, facing the way they should go");
    }

    private static boolean isSpawnSign(Block block) {
        if (!(block.getState() instanceof Sign sign)) {
            return false;
        }
        PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
        for (Side side : Side.values()) {
            String header = plain.serialize(sign.getSide(side).line(0));
            String action = plain.serialize(sign.getSide(side).line(1)).strip().toLowerCase(Locale.ROOT);
            if (TrainSign.isTrainHeader(header) && action.startsWith("spawn")) {
                return true;
            }
        }
        return false;
    }

    private static void reverse(Support support, CommandContext context) throws CommandError {
        Service service = support.service(context.arg(0));
        ServiceKey key = new ServiceKey(service.key().lineId(), Support.id("service", context.arg(1)));
        Line line = support.line(key.lineId());
        if (line.service(key.serviceId()).isPresent()) {
            throw new CommandError("Service " + key + " already exists");
        }
        List<Stop> reversed = new ArrayList<>();
        for (Stop stop : service.stops()) {
            reversed.add(new Stop(stop.stationId(), null, stop.pass()));
        }
        Collections.reverse(reversed);
        String name = context.restOr(2, service.name());
        Service opposite = Service.of(key, name, reversed).withCircular(service.circular());
        support.edit(network -> network.toBuilder()
                .line(network.line(line.id()).orElseThrow().withService(opposite)).build());
        Chat.success(context.sender(), "Created " + key + " with " + reversed.size()
                + " stops in reverse order. If trains turn around at the ends, run /atcs service continues "
                + service.key() + " " + key + " (and the other way round).");
    }

    private static void continues(Support support, CommandContext context) throws CommandError {
        Service service = support.service(context.arg(0));
        String target = context.arg(1);
        if (target.equalsIgnoreCase("none")) {
            save(support, service.withContinuesAs(null));
            Chat.success(context.sender(), "Trains finishing " + service.key() + " leave passenger service");
            return;
        }
        Service next = support.service(target);
        save(support, service.withContinuesAs(next.key()));
        Chat.success(context.sender(), "Trains finishing " + service.key() + " continue as " + next.key());
    }

    private static void match(Support support, CommandContext context) throws CommandError {
        Service service = support.service(context.arg(0));
        if (context.size() == 1) {
            ServiceMatch match = service.match();
            Chat.info(context.sender(), match.isEmpty() ? service.key() + " has no automatic assignment rules"
                    : service.key() + " assigns trains with destination " + match.destinations()
                    + ", tag " + match.tags() + ", train name " + match.trainNames());
            return;
        }
        String kind = context.arg(1).toLowerCase(Locale.ROOT);
        ServiceMatch match = service.match();
        ServiceMatch updated = switch (kind) {
            case "clear" -> ServiceMatch.NONE;
            case "destination" -> new ServiceMatch(append(match.destinations(), context.rest(2)), match.tags(), match.trainNames());
            case "tag" -> new ServiceMatch(match.destinations(), append(match.tags(), context.rest(2)), match.trainNames());
            case "train-name" -> new ServiceMatch(match.destinations(), match.tags(), append(match.trainNames(), context.rest(2)));
            default -> throw new CommandError("Use destination, tag, train-name or clear");
        };
        save(support, service.withMatch(updated));
        Chat.success(context.sender(), kind.equals("clear") ? "Cleared assignment rules of " + service.key()
                : "Trains with " + kind + " '" + context.rest(2) + "' now run " + service.key());
    }

    private static List<String> append(List<String> values, String value) {
        List<String> result = new ArrayList<>(values);
        if (!result.contains(value)) {
            result.add(value);
        }
        return result;
    }
}
