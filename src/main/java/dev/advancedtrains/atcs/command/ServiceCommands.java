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
import dev.advancedtrains.atcs.network.StopEdits;
import dev.advancedtrains.atcs.operation.ServiceResolver;
import dev.advancedtrains.atcs.operation.TrainInfo;
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
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
                leaf("addstop", MANAGE, "<line/service> <stop> [first|last|before <station>|after <station>]",
                        "Add one stop; the rest of the list and what was learned stay",
                        context -> addStop(support, context), (context, index) -> switch (index) {
                            case 0 -> support.serviceKeys();
                            case 1 -> stopSuggestions(support, context.argOr(1, ""));
                            case 2 -> List.of("first", "last", "before", "after");
                            case 3 -> support.stationIds();
                            default -> List.of();
                        }),
                leaf("removestop", MANAGE, "<line/service> <station>", "Drop a stop; the rest of the list and what was learned stay",
                        context -> removeStop(support, context), (context, index) -> switch (index) {
                            case 0 -> support.serviceKeys();
                            case 1 -> support.stationIds();
                            default -> List.of();
                        }),
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
                leaf("hours", MANAGE, "<line/service> <from-to|always>",
                        "When the service's fleet runs, for example 06:00-23:00; outside, trains are stabled at the end of their trip",
                        context -> hours(support, context), (context, index) -> switch (index) {
                            case 0 -> support.serviceKeys();
                            case 1 -> List.of("06:00-23:00", "always");
                            default -> List.of();
                        }),
                leaf("offpeak", MANAGE, "<line/service> <trains> <from-to>|off",
                        "Fewer fleet trains during part of the day, for example 2 22:00-06:00",
                        context -> offPeak(support, context), (context, index) -> switch (index) {
                            case 0 -> support.serviceKeys();
                            case 1 -> List.of("1", "2", "off");
                            case 2 -> List.of("22:00-06:00");
                            default -> List.of();
                        }),
                leaf("fleet", MANAGE, "<line/service> <trains> <every> <train>|off",
                        "Let ATCS keep trains running on a service without spawn signs; stand on the rail facing their way (a fleet keeps its spawn point when you stand elsewhere)",
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
                leaf("short", MANAGE, "<line/service> <text|none>", "Short name for sign lines the full name does not fit, for example CCW", context -> {
                    Service service = support.service(context.arg(0));
                    String text = context.rest(1).strip();
                    boolean clear = text.isEmpty() || text.equalsIgnoreCase("none") || text.equals("-");
                    save(support, service.withShortName(clear ? null : text));
                    Chat.success(context.sender(), clear
                            ? service.key() + " is shortened to " + service.withShortName(null).shortLabel() + " where its name does not fit"
                            : service.key() + " is shown as " + text + " where " + service.name() + " does not fit");
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
        if (service.shortName() != null) {
            sender.sendMessage(Chat.field("Short name", service.shortName() + " (on sign lines the name does not fit)"));
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
        // Every train TrainCarts stores for the service, loaded or not: what a fleet counts.
        Set<String> loaded = new HashSet<>();
        support.trains.loadedTrains().forEach(train -> loaded.add(train.name()));
        List<String> unloaded = new ArrayList<>();
        int assigned = 0;
        for (TrainInfo info : support.trains.allTrains()) {
            if (ServiceResolver.resolve(info, network).filter(service.key()::equals).isPresent()) {
                assigned++;
                if (!loaded.contains(info.name())) {
                    unloaded.add(info.name());
                }
            }
        }
        String trains = Long.toString(running);
        if (assigned != running || !unloaded.isEmpty()) {
            trains += " running, " + assigned + " assigned";
            if (!unloaded.isEmpty()) {
                trains += " (" + unloaded.size() + " not loaded: " + String.join(", ", unloaded) + ")";
            }
        }
        sender.sendMessage(Chat.field("Trains on this service", trains));
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
            stops.add(parseStop(network, text));
        }
        replaceStops(support, service, stops);
        Chat.success(context.sender(), service.key() + " now has " + stops.size() + " stops: " + describe(network, stops));
    }

    /** A stop from command text, checked against the network. */
    static Stop parseStop(Network network, String text) throws CommandError {
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
        return stop;
    }

    /** Saves a new stop list and carries learned times and recorded track over for the stops that stay. */
    static void replaceStops(Support support, Service service, List<Stop> stops) throws CommandError {
        Service updated = service.withStops(stops);
        save(support, updated);
        support.runtime.times().remap(service, updated);
        support.runtime.paths().remap(service, updated);
    }

    static String describe(Network network, List<Stop> stops) {
        return String.join(" > ", stops.stream()
                .map(stop -> (stop.pass() ? "(" : "") + network.stationName(stop.stationId()) + (stop.pass() ? ")" : ""))
                .toList());
    }

    private static void addStop(Support support, CommandContext context) throws CommandError {
        Service service = support.service(context.arg(0));
        Network network = support.network();
        Stop stop = parseStop(network, context.arg(1));
        String where = context.argOr(2, "last").toLowerCase(Locale.ROOT);
        StopEdits.Anchor anchor;
        String station = null;
        switch (where) {
            case "first" -> anchor = StopEdits.Anchor.FIRST;
            case "last" -> anchor = StopEdits.Anchor.LAST;
            case "before", "after" -> {
                anchor = where.equals("before") ? StopEdits.Anchor.BEFORE : StopEdits.Anchor.AFTER;
                station = support.station(context.arg(3)).id();
            }
            default -> throw new CommandError("Say where it goes: first, last, before <station> or after <station>");
        }
        List<Stop> stops;
        try {
            stops = StopEdits.insert(service.stops(), stop, anchor, station);
        } catch (IllegalArgumentException exception) {
            throw new CommandError(exception.getMessage());
        }
        replaceStops(support, service, stops);
        Chat.success(context.sender(), service.key() + " now has " + stops.size() + " stops: " + describe(network, stops));
    }

    private static void removeStop(Support support, CommandContext context) throws CommandError {
        Service service = support.service(context.arg(0));
        Network network = support.network();
        String station = support.station(context.arg(1)).id();
        List<Stop> stops;
        try {
            stops = StopEdits.remove(service.stops(), station);
        } catch (IllegalArgumentException exception) {
            throw new CommandError(exception.getMessage());
        }
        replaceStops(support, service, stops);
        Chat.success(context.sender(), service.key() + " now has " + stops.size() + " stops: " + describe(network, stops));
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

    private static Fleet fleetOf(Service service) throws CommandError {
        if (service.fleet() == null) {
            throw new CommandError(service.key() + " has no fleet; give it one first with /atcs service fleet " + service.key() + " <trains> <every> <train>");
        }
        return service.fleet();
    }

    private static void hours(Support support, CommandContext context) throws CommandError {
        Service service = support.service(context.arg(0));
        Fleet fleet = fleetOf(service);
        String text = context.arg(1);
        if (text.equalsIgnoreCase("always") || text.equalsIgnoreCase("off")) {
            save(support, service.withFleet(fleet.withHours(null)));
            Chat.success(context.sender(), service.key() + " runs all day");
            return;
        }
        try {
            save(support, service.withFleet(fleet.withHours(Fleet.Hours.parse(text))));
        } catch (IllegalArgumentException exception) {
            throw new CommandError(exception.getMessage());
        }
        Chat.success(context.sender(), service.key() + " runs " + text + "; outside those hours its trains are stabled at the end of their trip");
    }

    private static void offPeak(Support support, CommandContext context) throws CommandError {
        Service service = support.service(context.arg(0));
        Fleet fleet = fleetOf(service);
        if (context.arg(1).equalsIgnoreCase("off")) {
            save(support, service.withFleet(fleet.withOffPeak(null)));
            Chat.success(context.sender(), service.key() + " keeps " + fleet.trains() + " train(s) all day");
            return;
        }
        try {
            int trains = Integer.parseInt(context.arg(1));
            Fleet.OffPeak offPeak = new Fleet.OffPeak(trains, Fleet.Hours.parse(context.arg(2)));
            save(support, service.withFleet(fleet.withOffPeak(offPeak)));
            Chat.success(context.sender(), service.key() + " keeps " + trains + " train(s) " + offPeak.hours().format()
                    + " and " + fleet.trains() + " otherwise");
        } catch (NumberFormatException exception) {
            throw new CommandError("'" + context.arg(1) + "' is not a number of trains");
        } catch (IllegalArgumentException exception) {
            throw new CommandError(exception.getMessage());
        }
    }

    private static void fleet(Support support, CommandContext context) throws CommandError {
        Service service = support.service(context.arg(0));
        if (context.arg(1).equalsIgnoreCase("off")) {
            save(support, service.withFleet(null));
            Chat.success(context.sender(), service.key() + " no longer keeps a fleet; its trains stay as they are");
            return;
        }
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
        // The spawn point is the rail under the player; a service that has a fleet keeps its
        // spawn point when the sender stands elsewhere (or is the console), so the number of
        // trains, the interval or the train pattern can be changed from anywhere.
        Fleet existing = service.fleet();
        Block rail = context.sender() instanceof Player player ? railUnder(player) : null;
        BlockPos spawn;
        Fleet.Direction direction;
        boolean samePlace;
        if (rail != null) {
            spawn = Support.position(rail);
            direction = Fleet.Direction.fromYaw(((Player) context.sender()).getLocation().getYaw());
            samePlace = existing != null && existing.spawn().equals(spawn) && existing.direction() == direction;
        } else if (existing != null) {
            spawn = existing.spawn();
            direction = existing.direction();
            samePlace = true;
        } else {
            throw new CommandError("Stand on the rail where trains should appear, facing the way they should go");
        }
        Fleet fleet;
        try {
            fleet = new Fleet(trains, every, spawn, direction, pattern);
            if (existing != null) {
                fleet = fleet.withHours(existing.hours()).withOffPeak(existing.offPeak());
            }
        } catch (IllegalArgumentException exception) {
            throw new CommandError(exception.getMessage());
        }
        save(support, service.withFleet(fleet));
        Chat.success(context.sender(), service.key() + " keeps " + fleet.describe() + ". A train appears every "
                + Fleet.formatEvery(every) + " while fewer than " + trains + " are in service."
                + (rail == null ? " The spawn point stays at " + spawn.coordinates() + "." : ""));
    }

    /** The rail the player stands on, or null when there is none. */
    private static @Nullable Block railUnder(Player player) {
        Block at = player.getLocation().getBlock();
        for (Block candidate : List.of(at, at.getRelative(0, -1, 0))) {
            if (candidate.getBlockData() instanceof Rail) {
                return candidate;
            }
        }
        return null;
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
