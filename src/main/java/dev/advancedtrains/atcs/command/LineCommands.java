package dev.advancedtrains.atcs.command;

import dev.advancedtrains.atcs.network.Colors;
import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Service;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.CommandSender;

import java.util.List;

import static dev.advancedtrains.atcs.command.Node.group;
import static dev.advancedtrains.atcs.command.Node.leaf;
import static dev.advancedtrains.atcs.command.Support.MANAGE;
import static dev.advancedtrains.atcs.command.Support.USE;

/** {@code /atcs line ...} */
final class LineCommands {
    private static final List<String> COLORS = List.of("#E53935", "#1E88E5", "#43A047", "#FDD835", "#8E24AA",
            "#FB8C00", "red", "blue", "green", "gold", "aqua", "light_purple");

    private LineCommands() {
    }

    static Node build(Support support) {
        Node.Completer lines = (context, index) -> index == 0 ? support.lineIds() : List.of();
        return group("line", USE, "Lines and their colours",
                leaf("list", USE, "", "List lines", context -> list(support, context)),
                leaf("info", USE, "<line>", "Show a line and its services", context -> info(support, context), lines),
                leaf("create", MANAGE, "<id> <color> <name...>", "Create a line, e.g. /atcs line create a #E53935 Line A",
                        context -> create(support, context), (context, index) -> index == 1 ? COLORS : List.of()),
                leaf("rename", MANAGE, "<line> <name...>", "Change a line's name", context -> {
                    Line line = support.line(context.arg(0));
                    String name = context.rest(1);
                    support.edit(network -> network.toBuilder().line(line.withName(name)).build());
                    Chat.success(context.sender(), "Line " + line.id() + " is now called " + name);
                }, lines),
                leaf("color", MANAGE, "<line> <color>", "Change a line's colour (#RRGGBB or a colour name)", context -> {
                    Line line = support.line(context.arg(0));
                    int color = parseColor(context.arg(1));
                    support.edit(network -> network.toBuilder().line(line.withColor(color)).build());
                    Chat.success(context.sender(), "Line " + line.id() + " is now " + Colors.format(color));
                }, (context, index) -> index == 0 ? support.lineIds() : index == 1 ? COLORS : List.of()),
                leaf("badge", MANAGE, "<line> <text>", "Set the short badge shown on boss bars and boards", context -> {
                    Line line = support.line(context.arg(0));
                    String badge = context.rest(1);
                    support.edit(network -> network.toBuilder().line(line.withShortName(badge)).build());
                    Chat.success(context.sender(), "Line " + line.id() + " badge is now " + badge);
                }, lines),
                leaf("suspend", MANAGE, "<line> [notice...]", "Show a suspension notice to passengers and on boards", context -> {
                    Line line = support.line(context.arg(0));
                    String notice = context.restOr(1, "Please use alternative routes.");
                    support.edit(network -> network.toBuilder().line(line.withSuspension(notice)).build());
                    Chat.success(context.sender(), line.name() + " is marked as suspended: " + notice);
                }, lines),
                leaf("resume", MANAGE, "<line>", "Clear a suspension notice", context -> {
                    Line line = support.line(context.arg(0));
                    support.edit(network -> network.toBuilder().line(line.withSuspension(null)).build());
                    Chat.success(context.sender(), line.name() + " is running normally");
                }, lines),
                leaf("remove", MANAGE, "<line> [confirm]", "Remove a line and all its services", context -> {
                    Line line = support.line(context.arg(0));
                    if (!line.services().isEmpty() && !context.argOr(1, "").equalsIgnoreCase("confirm")) {
                        throw new CommandError(line.name() + " has " + line.services().size()
                                + " service(s). Run /atcs line remove " + line.id() + " confirm to remove them too.");
                    }
                    support.edit(network -> network.toBuilder().removeLine(line.id()).build());
                    Chat.success(context.sender(), "Removed " + line.name());
                }, (context, index) -> index == 0 ? support.lineIds() : List.of("confirm")));
    }

    private static int parseColor(String text) throws CommandError {
        try {
            return Colors.parse(text);
        } catch (IllegalArgumentException exception) {
            throw new CommandError(exception.getMessage());
        }
    }

    private static void list(Support support, CommandContext context) {
        Network network = support.network();
        if (network.lines().isEmpty()) {
            Chat.info(context.sender(), "No lines yet. Create one with /atcs line create <id> <color> <name>");
            return;
        }
        context.sender().sendMessage(Chat.header("Lines (" + network.lines().size() + ")"));
        for (Line line : network.lines().values()) {
            context.sender().sendMessage(Component.text()
                    .append(Chat.swatch(line.color()))
                    .append(Chat.run(line.id(), "/atcs line info " + line.id()))
                    .append(Component.text(" " + line.name(), NamedTextColor.WHITE))
                    .append(Component.text(" (" + line.services().size() + " services)", NamedTextColor.DARK_GRAY))
                    .append(line.suspended() ? Component.text(" suspended", NamedTextColor.RED) : Component.empty())
                    .build());
        }
    }

    private static void info(Support support, CommandContext context) throws CommandError {
        Line line = support.line(context.arg(0));
        Network network = support.network();
        CommandSender sender = context.sender();
        sender.sendMessage(Chat.header(line.name()));
        sender.sendMessage(Chat.field("Id", line.id()));
        sender.sendMessage(Chat.field("Badge", Component.text(line.shortName(), TextColor.color(line.color()))));
        sender.sendMessage(Chat.field("Colour", Component.text(line.colorHex(), TextColor.color(line.color()))));
        if (line.suspended()) {
            sender.sendMessage(Chat.field("Suspended", Component.text(line.suspension(), NamedTextColor.RED)));
        }
        if (line.services().isEmpty()) {
            sender.sendMessage(Component.text().append(Component.text("No services. ", NamedTextColor.GRAY))
                    .append(Chat.suggest("Create one", "/atcs service create " + line.id() + "/")).build());
        }
        for (Service service : line.services().values()) {
            String route = String.join(" > ", service.stops().stream()
                    .map(stop -> (stop.pass() ? "(" : "") + network.stationName(stop.stationId()) + (stop.pass() ? ")" : ""))
                    .toList());
            sender.sendMessage(Component.text()
                    .append(Chat.run(service.key().toString(), "/atcs service info " + service.key()))
                    .append(Component.text(" " + service.name() + ": ", NamedTextColor.WHITE))
                    .append(Component.text(route.isEmpty() ? "no stops" : route, NamedTextColor.GRAY))
                    .append(service.circular() ? Component.text(" (circular)", NamedTextColor.DARK_GRAY) : Component.empty())
                    .build());
        }
    }

    private static void create(Support support, CommandContext context) throws CommandError {
        String id = Support.id("line", context.arg(0));
        int color = parseColor(context.arg(1));
        String name = context.rest(2);
        if (support.network().line(id).isPresent()) {
            throw new CommandError("Line '" + id + "' already exists");
        }
        support.edit(network -> network.toBuilder().line(Line.of(id, name, color)).build());
        context.sender().sendMessage(Component.text()
                .append(Component.text("ATCS ", NamedTextColor.GOLD))
                .append(Component.text("Created ", NamedTextColor.GREEN))
                .append(Component.text(name, TextColor.color(color)))
                .append(Component.text(". Next: ", NamedTextColor.GREEN))
                .append(Chat.suggest("/atcs service create " + id + "/<service>", "/atcs service create " + id + "/"))
                .build());
    }
}
