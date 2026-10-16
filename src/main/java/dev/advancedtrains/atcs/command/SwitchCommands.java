package dev.advancedtrains.atcs.command;

import dev.advancedtrains.atcs.control.Heading;
import dev.advancedtrains.atcs.control.SwitchRules;
import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.runtime.RailwayRuntime;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static dev.advancedtrains.atcs.command.Node.group;
import static dev.advancedtrains.atcs.command.Node.leaf;
import static dev.advancedtrains.atcs.command.Support.MANAGE;

/** {@code /atcs switch ...}: the switch signs in loaded chunks, and directions set by hand. */
final class SwitchCommands {
    private SwitchCommands() {
    }

    static Node build(Support support) {
        Node.Completer positions = (context, index) -> index == 0
                ? support.runtime.switchSigns().keySet().stream().map(BlockPos::format).toList() : List.of();
        return group("switch", MANAGE, "Switch signs and directions set by hand",
                leaf("list", MANAGE, "", "List the switch signs in loaded chunks and any direction set by hand",
                        context -> list(support, context)),
                leaf("set", MANAGE, "<world,x,y,z> <direction> [minutes]",
                        "Send every train at the sign that way for a while (10 minutes unless given), whatever its rules say",
                        context -> set(support, context), (context, index) -> switch (index) {
                            case 0 -> positions.complete(context, 0);
                            case 1 -> List.of("left", "right", "continue", "north", "east", "south", "west");
                            case 2 -> List.of("5", "10", "30");
                            default -> List.of();
                        }),
                leaf("clear", MANAGE, "<world,x,y,z>", "Let the sign's rules decide again", context -> {
                    BlockPos sign = position(context.arg(0));
                    if (support.runtime.clearSwitch(sign)) {
                        Chat.success(context.sender(), "The switch at " + sign.coordinates() + " follows its rules again");
                    } else {
                        Chat.info(context.sender(), "Nothing was set by hand at " + sign.coordinates());
                    }
                }, positions));
    }

    private static BlockPos position(String text) throws CommandError {
        try {
            return BlockPos.parse(text);
        } catch (IllegalArgumentException exception) {
            throw new CommandError("Give the sign's position as world,x,y,z (see /atcs switch list)");
        }
    }

    private static void set(Support support, CommandContext context) throws CommandError {
        BlockPos sign = position(context.arg(0));
        Heading heading = Heading.parse(context.arg(1));
        if (heading == null) {
            throw new CommandError("'" + context.arg(1) + "' is not a direction: left, right, continue, reverse, north, east, south or west");
        }
        int minutes = 10;
        if (context.size() > 2) {
            try {
                minutes = Integer.parseInt(context.arg(2));
            } catch (NumberFormatException exception) {
                throw new CommandError("'" + context.arg(2) + "' is not a number of minutes");
            }
            if (minutes < 1 || minutes > 24 * 60) {
                throw new CommandError("Minutes must be between 1 and 1440");
            }
        }
        if (!support.runtime.switchSigns().containsKey(sign)) {
            Chat.warn(context.sender(), "No switch sign is loaded at " + sign.coordinates() + "; the setting applies once one is");
        }
        support.runtime.setSwitch(sign, heading, Duration.ofMinutes(minutes), context.sender().getName());
        Chat.success(context.sender(), "Every train at " + sign.coordinates() + " goes " + heading.word() + " for " + minutes
                + " minutes. /atcs switch clear " + sign.format() + " ends it sooner.");
    }

    private static void list(Support support, CommandContext context) {
        Map<BlockPos, SwitchRules> signs = support.runtime.switchSigns();
        Map<BlockPos, RailwayRuntime.SwitchOverride> overrides = support.runtime.switchOverrides();
        if (signs.isEmpty() && overrides.isEmpty()) {
            Chat.info(context.sender(), "No switch signs are loaded. They are listed once a train or a player loads their chunk.");
            return;
        }
        context.sender().sendMessage(Chat.header(signs.size() + " switch sign(s)"));
        signs.forEach((position, rules) -> {
            RailwayRuntime.SwitchOverride override = overrides.get(position);
            context.sender().sendMessage(Component.text()
                    .append(Component.text(position.format(), NamedTextColor.WHITE))
                    .append(Component.text(": " + rules.describe(), NamedTextColor.GRAY))
                    .append(override == null ? Component.empty()
                            : Component.text(" [set " + override.heading().word() + " by " + override.by() + "]", NamedTextColor.GOLD))
                    .build());
        });
        overrides.forEach((position, override) -> {
            if (!signs.containsKey(position)) {
                context.sender().sendMessage(Component.text(position.format() + ": set " + override.heading().word()
                        + " by " + override.by() + " (sign not loaded)", NamedTextColor.GOLD));
            }
        });
    }
}
