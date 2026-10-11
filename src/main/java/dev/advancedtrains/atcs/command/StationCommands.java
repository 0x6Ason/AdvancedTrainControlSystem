package dev.advancedtrains.atcs.command;

import dev.advancedtrains.atcs.importer.TrainSign;
import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Board;
import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Platform;
import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.Station;
import dev.advancedtrains.atcs.network.Stop;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
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
