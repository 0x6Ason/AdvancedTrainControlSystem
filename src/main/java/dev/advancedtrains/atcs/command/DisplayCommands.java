package dev.advancedtrains.atcs.command;

import dev.advancedtrains.atcs.display.DisplayService;
import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Box;
import dev.advancedtrains.atcs.network.Display;
import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.network.Station;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static dev.advancedtrains.atcs.command.Node.group;
import static dev.advancedtrains.atcs.command.Node.leaf;
import static dev.advancedtrains.atcs.command.Support.MANAGE;
import static dev.advancedtrains.atcs.command.Support.USE;

/** {@code /atcs display ...}, {@code /atcs wand} and {@code /atcs map}. */
final class DisplayCommands {
    private DisplayCommands() {
    }

    static Node wand(Support support) {
        return leaf("wand", MANAGE, "[pos1|pos2|clear|info]",
                "Get the selection wand: left click and right click two corners, then place displays or footprints",
                context -> wand(support, context),
                (context, index) -> index == 0 ? List.of("pos1", "pos2", "clear", "info") : List.of());
    }

    static Node map(Support support) {
        return leaf("map", USE, "", "Get a hand-held map of the railway that shows where you are",
                context -> handheld(support, context));
    }

    static Node build(Support support) {
        Node.Completer stationThenPlatforms = (context, index) -> index == 0 ? support.stationIds()
                : support.platformIds(context.argOr(0, ""));
        Node.Completer displays = (context, index) -> index == 0 ? List.copyOf(support.network().displays().keySet()) : List.of();
        return group("display", MANAGE, "Pictures in item frames and hologram boards, placed with the wand",
                leaf("roadmap", MANAGE, "<line[/service]> <station> [platform]",
                        "Fill the selected item frames with the line's strip diagram and 'you are here'",
                        context -> roadmap(support, context), (context, index) -> switch (index) {
                            case 0 -> {
                                List<String> keys = new ArrayList<>(support.lineIds());
                                keys.addAll(support.serviceKeys());
                                yield keys;
                            }
                            case 1 -> support.stationIds();
                            case 2 -> support.platformIds(context.argOr(1, ""));
                            default -> List.of();
                        }),
                leaf("logo", MANAGE, "<station>", "Fill the selected item frames with the station's logo",
                        context -> logo(support, context), (context, index) -> index == 0 ? support.stationIds() : List.of()),
                leaf("board", MANAGE, "<station> [platform...]",
                        "Fill the selected item frames with a departure board picture that keeps updating",
                        context -> board(support, context), stationThenPlatforms),
                leaf("map", MANAGE, "[station]", "Fill the selected item frames with the network map, marking the station",
                        context -> networkMap(support, context), (context, index) -> index == 0 ? support.stationIds() : List.of()),
                leaf("status", MANAGE, "", "Fill the selected item frames with a service status panel of every line",
                        context -> status(support, context)),
                leaf("hologram", MANAGE, "<station> [platform...]",
                        "Put a floating departure board at the selection's centre, or where you look",
                        context -> hologram(support, context), stationThenPlatforms),
                leaf("list", MANAGE, "", "List displays", context -> list(support, context)),
                leaf("remove", MANAGE, "<display>", "Take a display down: empty its frames or remove its hologram",
                        context -> remove(support, context), displays),
                leaf("redraw", MANAGE, "", "Draw every display again, for example after changing a logo file",
                        context -> {
                            support.displays().redrawAll();
                            Chat.success(context.sender(), "Displays are drawn again on the next refresh");
                        }));
    }

    // ---------------------------------------------------------------- wand

    private static void wand(Support support, CommandContext context) throws CommandError {
        Player player = context.player();
        String mode = context.argOr(0, "").toLowerCase(Locale.ROOT);
        switch (mode) {
            case "" -> {
                player.getInventory().addItem(support.selections().wand()).forEach((slot, left) ->
                        player.getWorld().dropItem(player.getLocation(), left));
                Chat.success(player, "You have the ATCS wand. Left click the first corner, right click the second"
                        + (support.selections().worldEditActive() ? "; a WorldEdit selection is used when you have one" : "")
                        + ". Then /atcs display ..., /atcs station footprint or /atcs station bindall.");
            }
            case "pos1", "pos2" -> {
                Location at = player.getLocation();
                BlockPos position = new BlockPos(at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
                support.selections().setCorner(player, mode.equals("pos1") ? 1 : 2, position);
                Chat.success(player, (mode.equals("pos1") ? "First" : "Second") + " corner set to where you stand, "
                        + position.coordinates() + describeSelection(support, player));
            }
            case "clear" -> {
                support.selections().clear(player);
                Chat.success(player, "Wand selection cleared");
            }
            case "info" -> {
                Optional<Box> selection = support.selections().selection(player);
                if (selection.isEmpty()) {
                    Chat.info(player, "No selection. Click two corners with the wand, or use /atcs wand pos1 and pos2.");
                } else {
                    Chat.info(player, "Selection from " + support.selections().source(player) + ": " + selection.get().describe());
                }
            }
            default -> throw new CommandError("Use /atcs wand, or /atcs wand pos1|pos2|clear|info");
        }
    }

    private static String describeSelection(Support support, Player player) {
        return support.selections().wandSelection(player).map(box -> " (" + box.volume() + " blocks selected)").orElse("");
    }

    static Box selection(Support support, Player player) throws CommandError {
        return support.selections().selection(player).orElseThrow(() -> new CommandError(
                "Select the area first: /atcs wand, then left click and right click two corners"
                        + (support.selections().worldEditActive() ? " (or make a WorldEdit selection)" : "")));
    }

    // ---------------------------------------------------------------- frames

    private static void roadmap(Support support, CommandContext context) throws CommandError {
        Network network = support.network();
        String target = context.arg(0);
        Service service;
        if (target.contains("/")) {
            service = support.service(target);
        } else {
            Line line = support.line(target);
            service = line.services().values().stream().max((a, b) -> Integer.compare(a.size(), b.size()))
                    .orElseThrow(() -> new CommandError("Line " + line.name() + " has no services yet"));
        }
        Station station = support.station(context.arg(1));
        if (!service.callsAt(station.id())) {
            throw new CommandError(service.key() + " does not call at " + station.name());
        }
        List<String> platforms = context.size() > 2 ? List.of(Support.id("platform", context.arg(2))) : List.of();
        if (!platforms.isEmpty() && !station.platforms().containsKey(platforms.get(0))) {
            throw new CommandError("Unknown platform '" + platforms.get(0) + "' at " + station.name());
        }
        Line line = network.line(service.key().lineId()).orElseThrow();
        String label = line.shortName() + " " + service.name() + " roadmap";
        fill(support, context, Display.Kind.ROADMAP, station.id(), platforms, service.key(), label,
                "Roadmap of " + service.key() + " at " + station.name());
    }

    private static void logo(Support support, CommandContext context) throws CommandError {
        Station station = support.station(context.arg(0));
        fill(support, context, Display.Kind.LOGO, station.id(), List.of(), null, station.name() + " logo",
                "Logo of " + station.name());
    }

    private static void board(Support support, CommandContext context) throws CommandError {
        Station station = support.station(context.arg(0));
        List<String> platforms = platforms(station, context, 1);
        fill(support, context, Display.Kind.BOARD, station.id(), platforms, null, station.name() + " departures",
                "Departure board of " + station.name() + (platforms.isEmpty() ? "" : " platform " + String.join(", ", platforms)));
    }

    private static void networkMap(Support support, CommandContext context) throws CommandError {
        Station station = context.size() > 0 ? support.station(context.arg(0)) : null;
        fill(support, context, Display.Kind.MAP, station == null ? null : station.id(), List.of(), null, "Railway map",
                "Network map" + (station == null ? "" : " marking " + station.name()));
    }

    private static void status(Support support, CommandContext context) throws CommandError {
        fill(support, context, Display.Kind.STATUS, null, List.of(), null, "Service status", "Service status panel");
    }

    private static List<String> platforms(Station station, CommandContext context, int from) throws CommandError {
        List<String> platforms = new ArrayList<>();
        for (int index = from; index < context.size(); index++) {
            String platform = Support.id("platform", context.arg(index));
            if (!station.platforms().containsKey(platform)) {
                throw new CommandError("Unknown platform '" + platform + "' at " + station.name());
            }
            platforms.add(platform);
        }
        return platforms;
    }

    private static void fill(Support support, CommandContext context, Display.Kind kind, @Nullable String stationId,
                             List<String> platforms, @Nullable ServiceKey service, String label, String what) throws CommandError {
        Player player = context.player();
        Box selection = selection(support, player);
        Network network = support.network();
        if (!support.store.writable()) {
            throw new CommandError("network.yml has errors; fix them and /atcs reload before adding displays");
        }
        DisplayService.FrameGrid grid;
        try {
            grid = support.displays().frames(player.getWorld(), selection, network);
        } catch (DisplayService.DisplayException exception) {
            throw new CommandError(exception.getMessage());
        }
        String id = freeId(network, (stationId == null ? "" : stationId + "-") + kind.key());
        Display display = support.displays().createFrames(id, kind, stationId, platforms, service, grid, label);
        support.edit(current -> current.toBuilder().display(display).build());
        Chat.success(context.sender(), what + " is now display " + id + " on " + grid.columns() + " x " + grid.rows()
                + " frames. /atcs display remove " + id + " takes it down.");
    }

    // ---------------------------------------------------------------- hologram

    private static void hologram(Support support, CommandContext context) throws CommandError {
        Player player = context.player();
        Station station = support.station(context.arg(0));
        List<String> platforms = platforms(station, context, 1);
        if (!support.store.writable()) {
            throw new CommandError("network.yml has errors; fix them and /atcs reload before adding displays");
        }
        Location at;
        Optional<Box> selection = support.selections().selection(player);
        if (selection.isPresent() && selection.get().world().equals(player.getWorld().getName())) {
            Box box = selection.get();
            at = new Location(player.getWorld(), box.centreX(), box.centreY(), box.centreZ());
        } else {
            Block looked = player.getTargetBlockExact(8);
            if (looked != null && !looked.getType().isAir()) {
                at = looked.getLocation().add(0.5, 1.2, 0.5);
            } else {
                at = player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(2));
            }
        }
        Network network = support.network();
        String id = freeId(network, station.id() + "-hologram");
        Display display = support.displays().createHologram(id, station.id(), platforms, at);
        support.edit(current -> current.toBuilder().display(display).build());
        Chat.success(context.sender(), "Hologram board " + id + " for " + station.name()
                + (platforms.isEmpty() ? "" : " platform " + String.join(", ", platforms)) + " placed at "
                + display.hologram().formatPosition() + ". /atcs display remove " + id + " takes it down.");
    }

    // ---------------------------------------------------------------- hand-held map

    private static void handheld(Support support, CommandContext context) throws CommandError {
        Player player = context.player();
        Network network = support.network();
        if (network.stations().isEmpty()) {
            throw new CommandError("There are no stations to draw yet");
        }
        Display display = support.displays().handheld(network, player.getWorld());
        if (network.display(DisplayService.HANDHELD_ID).map(existing -> !existing.equals(display)).orElse(true)) {
            if (!support.store.writable()) {
                throw new CommandError("network.yml has errors; fix them and /atcs reload first");
            }
            support.edit(current -> current.toBuilder().display(display).build());
        }
        ItemStack item = support.displays().handheldItem(display, "Railway map");
        player.getInventory().addItem(item).forEach((slot, left) -> player.getWorld().dropItem(player.getLocation(), left));
        Chat.success(player, "Here is the railway map; the arrow is you");
    }

    // ---------------------------------------------------------------- list and remove

    private static void list(Support support, CommandContext context) {
        Network network = support.network();
        if (network.displays().isEmpty()) {
            Chat.info(context.sender(), "No displays. Hang item frames, select them with /atcs wand, then /atcs display roadmap, logo, board, map or status; or /atcs display hologram.");
            return;
        }
        context.sender().sendMessage(Chat.header(network.displays().size() + " display(s)"));
        for (Display display : network.displays().values()) {
            List<String> problems = support.displays().check(display);
            Component line = Component.text()
                    .append(Component.text(display.id(), NamedTextColor.WHITE))
                    .append(Component.text(": " + display.describe(network), NamedTextColor.GRAY))
                    .append(problems.isEmpty() ? Component.empty()
                            : Component.text(" - " + String.join("; ", problems), NamedTextColor.RED))
                    .append(Component.text(" "))
                    .append(Chat.run("[remove]", "/atcs display remove " + display.id()))
                    .build();
            context.sender().sendMessage(line);
        }
    }

    private static void remove(Support support, CommandContext context) throws CommandError {
        Network network = support.network();
        String id = context.arg(0).toLowerCase(Locale.ROOT);
        Display display = network.display(id).orElseThrow(() -> new CommandError("Unknown display '" + id + "'. See /atcs display list"));
        List<String> notes = support.displays().remove(display);
        support.edit(current -> current.toBuilder().removeDisplay(display.id()).build());
        Chat.success(context.sender(), "Removed display " + display.id()
                + (notes.isEmpty() ? "" : " (" + String.join("; ", notes) + ")"));
    }

    private static String freeId(Network network, String base) {
        String candidate = base;
        int number = 2;
        while (network.displays().containsKey(candidate)) {
            candidate = base + "-" + number++;
        }
        return candidate;
    }
}
