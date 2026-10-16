package dev.advancedtrains.atcs.command;

import dev.advancedtrains.atcs.board.DepartureVariables;
import dev.advancedtrains.atcs.control.StationSignConversion;
import dev.advancedtrains.atcs.importer.ScannedSign;
import dev.advancedtrains.atcs.importer.TrainSign;
import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Board;
import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Platform;
import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.Station;
import dev.advancedtrains.atcs.network.StationCodes;
import dev.advancedtrains.atcs.network.Stop;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.block.SignChangeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.advancedtrains.atcs.command.Node.group;
import static dev.advancedtrains.atcs.command.Node.leaf;
import static dev.advancedtrains.atcs.command.Support.MANAGE;
import static dev.advancedtrains.atcs.command.Support.USE;

/** {@code /atcs station ...} */
final class StationCommands {
    private static final int PAGE_SIZE = 12;

    private StationCommands() {
    }

    static Node build(Support support) {
        return group("station", USE, "Stations and platforms",
                leaf("list", USE, "[page]", "List stations", context -> list(support, context)),
                leaf("info", USE, "<station>", "Show a station's platforms, triggers and lines",
                        context -> info(support, context), (context, index) -> index == 0 ? support.stationIds() : List.of()),
                leaf("here", USE, "", "Which station and platform you are at, its trains and the sign variables to use",
                        context -> here(support, context)),
                leaf("code", MANAGE, "<station> <code|auto>", "Set the short code used in sign variables, for example gb",
                        context -> code(support, context), (context, index) -> switch (index) {
                            case 0 -> support.stationIds();
                            case 1 -> List.of("auto");
                            default -> List.of();
                        }),
                leaf("create", MANAGE, "<id> <name...>", "Create a station", context -> create(support, context)),
                leaf("rename", MANAGE, "<station> <name...>", "Change a station's passenger name",
                        context -> rename(support, context), (context, index) -> index == 0 ? support.stationIds() : List.of()),
                leaf("remove", MANAGE, "<station> [confirm]", "Remove a station",
                        context -> remove(support, context), (context, index) -> index == 0 ? support.stationIds() : List.of("confirm")),
                leaf("bind", MANAGE, "<station> [platform]", "Use the TrainCarts sign you look at as a platform trigger",
                        context -> bind(support, context), (context, index) -> switch (index) {
                            case 0 -> support.stationIds();
                            case 1 -> support.platformIds(context.argOr(0, ""));
                            default -> List.of();
                        }),
                leaf("unbind", MANAGE, "", "Stop using the sign you look at as a trigger", context -> unbind(support, context)),
                leaf("bindall", MANAGE, "<station> [platform]",
                        "Bind every TrainCarts station sign in the wand selection as a platform trigger",
                        context -> bindAll(support, context), (context, index) -> switch (index) {
                            case 0 -> support.stationIds();
                            case 1 -> support.platformIds(context.argOr(0, ""));
                            default -> List.of();
                        }),
                leaf("footprint", MANAGE, "<station> [clear]",
                        "Make the wand selection the station's footprint: players inside it are at the station",
                        context -> footprint(support, context), (context, index) -> switch (index) {
                            case 0 -> support.stationIds();
                            case 1 -> List.of("clear");
                            default -> List.of();
                        }),
                leaf("convert", MANAGE, "[confirm]",
                        "Turn the TrainCarts station signs of the platforms in network.yml into atcs station signs",
                        context -> convert(support, context), (context, index) -> index == 0 ? List.of("confirm") : List.of()),
                leaf("platform", MANAGE, "<station> <platform> [name...]", "Create or rename a platform",
                        context -> platform(support, context), (context, index) -> switch (index) {
                            case 0 -> support.stationIds();
                            case 1 -> support.platformIds(context.argOr(0, ""));
                            default -> List.of();
                        }),
                leaf("locate", MANAGE, "<station>", "Show the station on maps where you stand",
                        context -> locate(support, context), (context, index) -> index == 0 ? support.stationIds() : List.of()));
    }

    private static void list(Support support, CommandContext context) throws CommandError {
        Network network = support.network();
        List<Station> stations = List.copyOf(network.stations().values());
        if (stations.isEmpty()) {
            Chat.info(context.sender(), "No stations yet. Create one with /atcs station create, or import them with /atcs import scan.");
            return;
        }
        int pages = (stations.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int page = parsePage(context.argOr(0, "1"), pages);
        context.sender().sendMessage(Chat.header("Stations (" + stations.size() + ") page " + page + "/" + pages));
        for (Station station : stations.subList((page - 1) * PAGE_SIZE, Math.min(stations.size(), page * PAGE_SIZE))) {
            TextComponent.Builder line = Component.text()
                    .append(Chat.run(station.id(), "/atcs station info " + station.id()))
                    .append(Component.text(" " + station.name(), NamedTextColor.WHITE))
                    .append(Component.text(" (" + station.platforms().size() + " platforms) ", NamedTextColor.DARK_GRAY));
            for (Line calling : network.linesCallingAt(station.id())) {
                line.append(Chat.swatch(calling.color()));
            }
            context.sender().sendMessage(line.build());
        }
    }

    static int parsePage(String text, int pages) throws CommandError {
        try {
            int page = Integer.parseInt(text);
            if (page < 1 || page > Math.max(1, pages)) {
                throw new CommandError("Page must be between 1 and " + Math.max(1, pages));
            }
            return page;
        } catch (NumberFormatException exception) {
            throw new CommandError("'" + text + "' is not a page number");
        }
    }

    private static void info(Support support, CommandContext context) throws CommandError {
        Station station = support.station(context.arg(0));
        Network network = support.network();
        CommandSender sender = context.sender();
        sender.sendMessage(Chat.header(station.name()));
        sender.sendMessage(Chat.field("Id", station.id()));
        String code = network.code(station.id());
        sender.sendMessage(Chat.field("Sign code", Component.text()
                .append(Component.text(code + (station.code() == null ? " (automatic) " : " "), NamedTextColor.WHITE))
                .append(copy("%" + DepartureVariables.name(network, station.id(), null, 1, DepartureVariables.TIME) + "%"))
                .append(Component.text(" next train's time", NamedTextColor.GRAY)).build()));
        station.markerPosition().ifPresent(position -> sender.sendMessage(
                Chat.field(station.location() != null ? "Map position" : "Map position (from triggers)",
                        position.world() + " " + position.coordinates())));
        if (station.platforms().isEmpty()) {
            sender.sendMessage(Chat.field("Platforms", "none - bind a TrainCarts station sign with /atcs station bind " + station.id()));
        }
        for (Platform platform : station.platforms().values()) {
            String triggers = platform.triggers().isEmpty() ? "no triggers"
                    : String.join(", ", platform.triggers().stream().map(BlockPos::coordinates).toList());
            sender.sendMessage(Chat.field("Platform " + platform.displayName(), triggers));
        }
        List<Line> lines = network.linesCallingAt(station.id());
        if (!lines.isEmpty()) {
            TextComponent.Builder text = Component.text();
            for (Line line : lines) {
                text.append(Chat.swatch(line.color())).append(Component.text(line.name() + "  ", NamedTextColor.WHITE));
            }
            sender.sendMessage(Chat.field("Lines", text.build()));
        }
        List<String> services = network.servicesListing(station.id()).stream().map(service -> service.key().toString()).toList();
        if (!services.isEmpty()) {
            sender.sendMessage(Chat.field("Services", String.join(", ", services)));
        }
    }

    /** Text that copies itself to the clipboard when clicked. */
    private static Component copy(String text) {
        return Component.text(text, NamedTextColor.AQUA)
                .clickEvent(ClickEvent.copyToClipboard(text))
                .hoverEvent(HoverEvent.showText(Component.text("Click to copy", NamedTextColor.GRAY)));
    }

    private static final int HERE_RADIUS = 48;

    /**
     * Tells the player which platform they are at: the one whose trigger sign they look at, or
     * the one with the nearest trigger. Lists the trains that use it and the sign variables to
     * show its departures.
     */
    private static void here(Support support, CommandContext context) throws CommandError {
        Player player = context.player();
        Network network = support.network();
        PlatformRef found = null;
        Block looked = player.getTargetBlockExact(6);
        if (looked != null) {
            found = network.platformAt(Support.position(looked)).orElse(null);
        }
        if (found == null) {
            Location at = player.getLocation();
            String world = at.getWorld() == null ? "" : at.getWorld().getName();
            // Inside a station's footprint, only that station's platforms count; the nearest wins.
            Station inside = network.stationAt(world, at.getX(), at.getY(), at.getZ()).orElse(null);
            double best = inside == null ? HERE_RADIUS * HERE_RADIUS : Double.MAX_VALUE;
            for (Map.Entry<BlockPos, PlatformRef> trigger : network.triggers().entrySet()) {
                if (!trigger.getKey().world().equals(world)
                        || inside != null && !trigger.getValue().stationId().equals(inside.id())) {
                    continue;
                }
                double distance = trigger.getKey().distanceSquared(at.getX(), at.getY(), at.getZ());
                if (distance < best) {
                    best = distance;
                    found = trigger.getValue();
                }
            }
            if (found == null && inside != null) {
                context.sender().sendMessage(Chat.header(inside.name()));
                Chat.info(context.sender(), "You are inside the footprint of " + inside.name()
                        + ", which has no platform triggers yet. Bind its station signs with /atcs station bind or bindall.");
                return;
            }
        }
        if (found == null) {
            throw new CommandError("No platform within " + HERE_RADIUS + " blocks. Stand on a platform, look at its"
                    + " TrainCarts station sign, or give the station a footprint with /atcs station footprint.");
        }
        Station station = network.station(found.stationId()).orElseThrow();
        Platform platform = station.platform(found.platformId()).orElseThrow();
        CommandSender sender = context.sender();
        sender.sendMessage(Chat.header(station.name() + " - platform " + platform.displayName()));

        List<Component> trains = new ArrayList<>();
        for (Service service : network.servicesListing(station.id())) {
            for (int index = 0; index < service.size(); index++) {
                Stop stop = service.stop(index);
                if (!stop.stationId().equals(station.id()) || stop.pass()) {
                    continue;
                }
                String used = stop.platformId() != null ? stop.platformId()
                        : support.runtime.times().usualPlatform(service, index).orElse(null);
                if (!platform.id().equals(used)) {
                    continue;
                }
                Line line = network.line(service.key().lineId()).orElseThrow();
                String toward = service.destinationFrom(index).map(next -> " to " + network.stationName(next.stationId()))
                        .orElse(service.circular() ? " (loop)" : "");
                trains.add(Component.text()
                        .append(Chat.swatch(line.color()))
                        .append(Component.text(line.name() + " " + service.name() + toward, NamedTextColor.WHITE)).build());
            }
        }
        if (trains.isEmpty()) {
            sender.sendMessage(Chat.field("Trains", "none known yet; trains that stop here teach ATCS their platform"));
        } else {
            sender.sendMessage(Component.text("Trains from this platform:", NamedTextColor.GRAY));
            trains.forEach(sender::sendMessage);
        }

        sender.sendMessage(Component.text("Sign variables (SignLink), click to copy:", NamedTextColor.GRAY));
        String[][] rows = {
                {DepartureVariables.DESTINATION, "next train's destination"},
                {DepartureVariables.TIME, "its time: 0:05, 45s or Boarding"},
                {DepartureVariables.LINE, "its line"},
                {DepartureVariables.SERVICE, "its service"},
        };
        for (String[] row : rows) {
            sender.sendMessage(Component.text()
                    .append(copy("%" + DepartureVariables.name(network, station.id(), platform.id(), 1, row[0]) + "%"))
                    .append(Component.text("  " + row[1], NamedTextColor.GRAY)).build());
        }
        sender.sendMessage(Component.text()
                .append(Component.text("Train after: ", NamedTextColor.GRAY))
                .append(copy("%" + DepartureVariables.name(network, station.id(), platform.id(), 2, DepartureVariables.DESTINATION) + "%"))
                .append(Component.text(" ", NamedTextColor.GRAY))
                .append(copy("%" + DepartureVariables.name(network, station.id(), platform.id(), 2, DepartureVariables.TIME) + "%")).build());
        sender.sendMessage(Component.text()
                .append(Component.text("All platforms: ", NamedTextColor.GRAY))
                .append(copy("%" + DepartureVariables.name(network, station.id(), null, 1, DepartureVariables.DESTINATION) + "%"))
                .append(Component.text(" ", NamedTextColor.GRAY))
                .append(copy("%" + DepartureVariables.name(network, station.id(), null, 1, DepartureVariables.TIME) + "%"))
                .append(Component.text(" ", NamedTextColor.GRAY))
                .append(copy("%" + DepartureVariables.name(network, station.id(), null, 1, DepartureVariables.PLATFORM) + "%"))
                .append(Component.text(" (platform)", NamedTextColor.GRAY)).build());
    }

    private static void code(Support support, CommandContext context) throws CommandError {
        Station station = support.station(context.arg(0));
        String wanted = context.arg(1);
        String code = null;
        if (!wanted.equalsIgnoreCase("auto")) {
            try {
                code = StationCodes.normalize(wanted);
            } catch (IllegalArgumentException exception) {
                throw new CommandError(exception.getMessage());
            }
            Network network = support.network();
            Optional<Station> owner = network.stationByCode(code);
            if (owner.isPresent() && !owner.get().id().equals(station.id())) {
                throw new CommandError(owner.get().name() + " already uses the code " + code);
            }
        }
        String explicit = code;
        Network updated = support.edit(network -> network.toBuilder()
                .station(network.station(station.id()).orElseThrow().withCode(explicit)).build());
        String result = updated.code(station.id());
        context.sender().sendMessage(Component.text()
                .append(Component.text("ATCS ", NamedTextColor.GOLD))
                .append(Component.text(station.name() + " now uses the code " + result + (explicit == null ? " (automatic)" : "")
                        + ". Next train's time: ", NamedTextColor.GREEN))
                .append(copy("%" + DepartureVariables.name(updated, station.id(), null, 1, DepartureVariables.TIME) + "%")).build());
    }

    private static void create(Support support, CommandContext context) throws CommandError {
        String id = Support.id("station", context.arg(0));
        String name = context.rest(1);
        if (support.network().station(id).isPresent()) {
            throw new CommandError("Station '" + id + "' already exists");
        }
        support.edit(network -> network.toBuilder().station(Station.of(id, name)).build());
        Chat.success(context.sender(), "Created station " + id + " (" + name + "). Next: look at its TrainCarts station sign and run /atcs station bind " + id);
    }

    private static void rename(Support support, CommandContext context) throws CommandError {
        Station station = support.station(context.arg(0));
        String name = context.rest(1);
        support.edit(network -> network.toBuilder().station(station.withName(name)).build());
        Chat.success(context.sender(), "Station " + station.id() + " is now called " + name);
    }

    private static void remove(Support support, CommandContext context) throws CommandError {
        Station station = support.station(context.arg(0));
        Network network = support.network();
        List<Service> services = network.servicesListing(station.id());
        List<Board> boards = network.boards().values().stream()
                .filter(board -> board.stationId().equals(station.id())).toList();
        boolean confirmed = context.argOr(1, "").equalsIgnoreCase("confirm");
        if ((!services.isEmpty() || !boards.isEmpty()) && !confirmed) {
            throw new CommandError("Station " + station.id() + " is used by " + services.size() + " service(s) and "
                    + boards.size() + " board(s). Run /atcs station remove " + station.id()
                    + " confirm to remove it together with those stops and boards.");
        }
        support.edit(current -> {
            Network.Builder builder = current.toBuilder().removeStation(station.id());
            for (Line line : current.lines().values()) {
                Line updated = line;
                for (Service service : line.services().values()) {
                    List<Stop> stops = service.stops().stream().filter(stop -> !stop.stationId().equals(station.id())).toList();
                    if (stops.size() != service.size()) {
                        updated = updated.withService(service.withStops(stops));
                    }
                }
                builder.line(updated);
            }
            boards.forEach(board -> builder.removeBoard(board.id()));
            current.transfersAt(station.id()).forEach(transfer -> builder.removeTransfer(transfer.a(), transfer.b()));
            return builder.build();
        });
        Chat.success(context.sender(), "Removed station " + station.id());
    }

    private static void bind(Support support, CommandContext context) throws CommandError {
        Player player = context.player();
        Station station = support.station(context.arg(0));
        Block block = Support.targetSign(player);
        BlockPos position = Support.position(block);
        if (!isTrainCartsSign(block)) {
            throw new CommandError("That is not a TrainCarts sign. Bind the station sign trains stop at, or place an 'atcs stop' sign.");
        }
        Network network = support.network();
        Optional<PlatformRef> existing = network.platformAt(position);
        if (existing.isPresent()) {
            throw new CommandError("That sign is already the trigger of " + network.stationName(existing.get().stationId())
                    + " platform " + existing.get().platformId() + ". Use /atcs station unbind first.");
        }
        if (network.isBoardSign(position)) {
            throw new CommandError("That sign is part of a departure board");
        }
        String platformId = Support.id("platform", context.argOr(1,
                station.platforms().isEmpty() ? "1" : nextPlatformId(station)));
        Platform platform = station.platform(platformId).orElse(Platform.of(platformId)).withTrigger(position);
        support.edit(current -> current.toBuilder()
                .station(current.station(station.id()).orElseThrow().withPlatform(platform)).build());
        Chat.success(context.sender(), "Trains at " + position.coordinates() + " are now at " + station.name()
                + " platform " + platform.displayName());
    }

    /** Binds every TrainCarts station sign inside the wand selection, each as its own platform unless one is named. */
    private static void bindAll(Support support, CommandContext context) throws CommandError {
        Player player = context.player();
        Station station = support.station(context.arg(0));
        dev.advancedtrains.atcs.network.Box selection = DisplayCommands.selection(support, player);
        if (selection.volume() > 200_000) {
            throw new CommandError("That selection is " + selection.volume() + " blocks; select just the platforms");
        }
        World world = Bukkit.getWorld(selection.world());
        if (world == null) {
            throw new CommandError("World " + selection.world() + " is not loaded");
        }
        String fixedPlatform = context.size() > 1 ? Support.id("platform", context.arg(1)) : null;
        Network network = support.network();
        List<Block> signs = new ArrayList<>();
        int already = 0;
        for (int chunkX = selection.minX() >> 4; chunkX <= selection.maxX() >> 4; chunkX++) {
            for (int chunkZ = selection.minZ() >> 4; chunkZ <= selection.maxZ() >> 4; chunkZ++) {
                for (org.bukkit.block.BlockState state : world.getChunkAt(chunkX, chunkZ).getTileEntities()) {
                    Block block = state.getBlock();
                    BlockPos position = Support.position(block);
                    if (!selection.contains(position) || !isTrainCartsSign(block)) {
                        continue;
                    }
                    Optional<TrainSign> parsed = TrainSign.parse(new ScannedSign(position, true, plainLines(block)));
                    if (parsed.isEmpty() || !parsed.get().action().equals("station")) {
                        continue;
                    }
                    if (network.platformAt(position).isPresent()) {
                        already++;
                        continue;
                    }
                    signs.add(block);
                }
            }
        }
        if (signs.isEmpty()) {
            throw new CommandError("No unbound TrainCarts station signs in the selection (" + selection.describe() + ")"
                    + (already > 0 ? "; " + already + " already bound" : ""));
        }
        signs.sort(java.util.Comparator.comparingInt(Block::getX).thenComparingInt(Block::getZ).thenComparingInt(Block::getY));
        List<String> bound = new ArrayList<>();
        support.edit(current -> {
            Station updated = current.station(station.id()).orElseThrow();
            for (Block block : signs) {
                BlockPos position = Support.position(block);
                String platformId = fixedPlatform != null ? fixedPlatform
                        : updated.platforms().isEmpty() ? "1" : nextPlatformId(updated);
                Platform platform = updated.platform(platformId).orElse(Platform.of(platformId)).withTrigger(position);
                updated = updated.withPlatform(platform);
                bound.add(position.coordinates() + " -> platform " + platformId);
            }
            return current.toBuilder().station(updated).build();
        });
        Chat.success(context.sender(), "Bound " + bound.size() + " sign(s) at " + station.name()
                + (already > 0 ? " (" + already + " were already bound)" : "") + ":");
        bound.forEach(line -> context.sender().sendMessage(Component.text("  " + line, NamedTextColor.GRAY)));
    }

    private static List<String> plainLines(Block block) {
        List<String> lines = new ArrayList<>();
        if (block.getState() instanceof Sign sign) {
            for (Component line : sign.getSide(Side.FRONT).lines()) {
                lines.add(PlainTextComponentSerializer.plainText().serialize(line));
            }
        }
        return lines;
    }

    /** Sets or clears the station's footprint from the wand selection. */
    private static void footprint(Support support, CommandContext context) throws CommandError {
        Station station = support.station(context.arg(0));
        if (context.argOr(1, "").equalsIgnoreCase("clear")) {
            support.edit(current -> current.toBuilder()
                    .station(current.station(station.id()).orElseThrow().withFootprint(null)).build());
            Chat.success(context.sender(), station.name() + " has no footprint any more");
            return;
        }
        Player player = context.player();
        dev.advancedtrains.atcs.network.Box selection = DisplayCommands.selection(support, player);
        Network network = support.network();
        for (Station other : network.stations().values()) {
            if (other.id().equals(station.id()) || other.footprint() == null) {
                continue;
            }
            dev.advancedtrains.atcs.network.Box theirs = other.footprint();
            boolean overlaps = theirs.world().equals(selection.world())
                    && theirs.minX() <= selection.maxX() && selection.minX() <= theirs.maxX()
                    && theirs.minY() <= selection.maxY() && selection.minY() <= theirs.maxY()
                    && theirs.minZ() <= selection.maxZ() && selection.minZ() <= theirs.maxZ();
            if (overlaps) {
                throw new CommandError("That overlaps the footprint of " + other.name() + " (" + theirs.describe() + ")");
            }
        }
        support.edit(current -> current.toBuilder()
                .station(current.station(station.id()).orElseThrow().withFootprint(selection)).build());
        Chat.success(context.sender(), station.name() + " now occupies " + selection.describe()
                + " (from " + support.selections().source(player) + ")");
    }

    private record PlannedConversion(Block block, PlatformRef platform, StationSignConversion conversion) {
    }

    /**
     * Converts the TrainCarts {@code station} signs that are platform triggers in network.yml into
     * {@code atcs station} signs, through the same sign-change event a player editing them would
     * cause, so TrainCarts and ATCS build them as usual. Signs that are not bound as platforms
     * (refill stops, test stations) are never touched; without {@code confirm} nothing is.
     */
    private static void convert(Support support, CommandContext context) throws CommandError {
        Player player = context.player();
        boolean confirm = context.argOr(0, "").equalsIgnoreCase("confirm");
        Network network = support.network();
        CommandSender sender = context.sender();
        List<PlannedConversion> planned = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        int already = 0;
        int other = 0;
        for (Map.Entry<BlockPos, PlatformRef> trigger : network.triggers().entrySet()) {
            BlockPos position = trigger.getKey();
            World world = Bukkit.getWorld(position.world());
            if (world == null) {
                problems.add(position.coordinates() + ": world '" + position.world() + "' is not loaded");
                continue;
            }
            world.getChunkAt(position.chunkX(), position.chunkZ());
            Block block = world.getBlockAt(position.x(), position.y(), position.z());
            if (!(block.getState() instanceof Sign sign)) {
                problems.add(position.coordinates() + " (" + trigger.getValue() + "): no sign there");
                continue;
            }
            List<String> lines = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                lines.add(PlainTextComponentSerializer.plainText().serialize(sign.getSide(Side.FRONT).line(index)));
            }
            if (StationSignConversion.isAtcsSign(lines)) {
                already++;
                continue;
            }
            Optional<StationSignConversion> conversion = StationSignConversion.convert(lines, trigger.getValue());
            if (conversion.isEmpty()) {
                other++;
                continue;
            }
            planned.add(new PlannedConversion(block, trigger.getValue(), conversion.get()));
        }
        sender.sendMessage(Chat.header("Station signs"));
        for (PlannedConversion item : planned) {
            sender.sendMessage(Component.text()
                    .append(Component.text(network.stationName(item.platform().stationId()) + " platform "
                            + item.platform().platformId() + " at " + Support.position(item.block()).coordinates() + ": ", NamedTextColor.WHITE))
                    .append(Component.text("atcs station / " + item.platform() + " / " + item.conversion().lines().get(3), NamedTextColor.AQUA))
                    .build());
            for (String note : item.conversion().notes()) {
                sender.sendMessage(Component.text("    " + note, NamedTextColor.GOLD));
            }
        }
        problems.forEach(problem -> sender.sendMessage(Component.text(problem, NamedTextColor.RED)));
        sender.sendMessage(Component.text(planned.size() + " TrainCarts station sign(s) to convert, " + already
                + " already converted, " + other + " other sign(s) left alone", NamedTextColor.GRAY));
        if (planned.isEmpty()) {
            return;
        }
        if (!confirm) {
            sender.sendMessage(Component.text()
                    .append(Component.text("Nothing changed yet. ", NamedTextColor.GRAY))
                    .append(Chat.run("Run /atcs station convert confirm", "/atcs station convert confirm"))
                    .append(Component.text(" to rewrite these signs.", NamedTextColor.GRAY)).build());
            return;
        }
        int converted = 0;
        for (PlannedConversion item : planned) {
            List<Component> lines = item.conversion().lines().stream().map(line -> (Component) Component.text(line)).toList();
            SignChangeEvent event = new SignChangeEvent(item.block(), player, new ArrayList<>(lines), Side.FRONT);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                sender.sendMessage(Component.text(Support.position(item.block()).coordinates()
                        + ": another plugin refused the change", NamedTextColor.RED));
                continue;
            }
            if (!(item.block().getState() instanceof Sign sign)) {
                continue;
            }
            SignSide front = sign.getSide(Side.FRONT);
            List<Component> result = event.lines();
            for (int index = 0; index < 4; index++) {
                Component line = index < result.size() ? result.get(index) : null;
                front.line(index, line == null ? Component.empty() : line);
            }
            sign.update();
            converted++;
        }
        Chat.success(sender, "Converted " + converted + " sign(s). ATCS now stops the trains at those platforms;"
                + " watch /atcs debug on for 'stops at' and 'runs through'.");
    }

    private static String nextPlatformId(Station station) {
        int number = 1;
        while (station.platforms().containsKey(Integer.toString(number))) {
            number++;
        }
        return Integer.toString(number);
    }

    static boolean isTrainCartsSign(Block block) {
        if (!(block.getState() instanceof Sign sign)) {
            return false;
        }
        for (Side side : Side.values()) {
            if (TrainSign.isTrainHeader(PlainTextComponentSerializer.plainText().serialize(sign.getSide(side).line(0)))) {
                return true;
            }
        }
        return false;
    }

    private static void unbind(Support support, CommandContext context) throws CommandError {
        Block block = Support.targetSign(context.player());
        BlockPos position = Support.position(block);
        PlatformRef ref = support.network().platformAt(position)
                .orElseThrow(() -> new CommandError("That sign is not bound to a platform"));
        support.edit(current -> {
            Station station = current.station(ref.stationId()).orElseThrow();
            Platform platform = station.platform(ref.platformId()).orElseThrow().withoutTrigger(position);
            return current.toBuilder().station(station.withPlatform(platform)).build();
        });
        Chat.success(context.sender(), "Unbound " + position.coordinates() + " from " + ref);
    }

    private static void platform(Support support, CommandContext context) throws CommandError {
        Station station = support.station(context.arg(0));
        String platformId = Support.id("platform", context.arg(1));
        String name = context.restOr(2, "");
        Platform platform = station.platform(platformId).map(existing -> existing.withName(name))
                .orElse(new Platform(platformId, name, List.of()));
        support.edit(current -> current.toBuilder()
                .station(current.station(station.id()).orElseThrow().withPlatform(platform)).build());
        Chat.success(context.sender(), "Platform " + platform.displayName() + " of " + station.name() + " saved");
    }

    private static void locate(Support support, CommandContext context) throws CommandError {
        Station station = support.station(context.arg(0));
        Location location = context.player().getLocation();
        BlockPos position = new BlockPos(location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
        support.edit(current -> current.toBuilder().station(station.withLocation(position)).build());
        Chat.success(context.sender(), station.name() + " is shown on maps at " + position.coordinates());
    }
}
