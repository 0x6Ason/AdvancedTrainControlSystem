package dev.advancedtrains.atcs.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;

/** Consistent formatting for command output. */
public final class Chat {
    private static final Component PREFIX = Component.text("ATCS ", NamedTextColor.GOLD, TextDecoration.BOLD);

    private Chat() {
    }

    public static void info(CommandSender sender, String message) {
        sender.sendMessage(PREFIX.append(Component.text(message, NamedTextColor.GRAY)));
    }

    public static void success(CommandSender sender, String message) {
        sender.sendMessage(PREFIX.append(Component.text(message, NamedTextColor.GREEN)));
    }

    public static void warn(CommandSender sender, String message) {
        sender.sendMessage(PREFIX.append(Component.text(message, NamedTextColor.GOLD)));
    }

    public static void error(CommandSender sender, String message) {
        sender.sendMessage(PREFIX.append(Component.text(message, NamedTextColor.RED)));
    }

    public static Component header(String title) {
        return Component.text()
                .append(Component.text("--- ", NamedTextColor.DARK_GRAY))
                .append(Component.text(title, NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text(" ---", NamedTextColor.DARK_GRAY))
                .build();
    }

    /** {@code key: value} on one line. */
    public static Component field(String key, Component value) {
        return Component.text().append(Component.text(key + ": ", NamedTextColor.GRAY)).append(value).build();
    }

    public static Component field(String key, String value) {
        return field(key, Component.text(value, NamedTextColor.WHITE));
    }

    public static TextComponent swatch(int rgb) {
        return Component.text("■ ", TextColor.color(rgb));
    }

    /** Clickable text that suggests a command. */
    public static Component suggest(String text, String command) {
        return Component.text(text, NamedTextColor.AQUA)
                .clickEvent(ClickEvent.suggestCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(command, NamedTextColor.GRAY)));
    }

    /** Clickable text that runs a command. */
    public static Component run(String text, String command) {
        return Component.text(text, NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(command, NamedTextColor.GRAY)));
    }
}
