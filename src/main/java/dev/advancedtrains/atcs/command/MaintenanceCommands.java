package dev.advancedtrains.atcs.command;

import dev.advancedtrains.atcs.fare.FareTable;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.NetworkLint;
import dev.advancedtrains.atcs.network.NetworkStore;
import dev.advancedtrains.atcs.operation.RoutePlanner;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Sign;
import org.bukkit.command.CommandSender;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** {@code /atcs lint}, {@code /atcs undo} and {@code /atcs history}. */
final class MaintenanceCommands {
    private MaintenanceCommands() {
    }

    static void lint(Support support, CommandContext context) {
        Network network = support.network();
        FareTable fares = support.fares == null ? null : support.fares.store().table();
        RoutePlanner planner = support.fares == null ? null : support.fares.calculator().planner();
        NetworkLint.Inputs inputs = new NetworkLint.Inputs(
                support.runtime.observedTriggers(),
                MaintenanceCommands::signExists,
                service -> support.runtime.times().learnedSegments(service),
                fares,
                planner == null ? null : (a, b) -> planner.route(a, b).isPresent());
        List<NetworkLint.Finding> findings = NetworkLint.check(network, inputs);
        CommandSender sender = context.sender();
        sender.sendMessage(Chat.header("Network check"));
        if (findings.isEmpty()) {
            Chat.success(sender, "Nothing to report: every station has a platform with a sign, every line runs, every board stands.");
            return;
        }
        for (NetworkLint.Level level : NetworkLint.Level.values()) {
            List<NetworkLint.Finding> ofLevel = findings.stream().filter(finding -> finding.level() == level).toList();
            if (ofLevel.isEmpty()) {
                continue;
            }
            NamedTextColor color = switch (level) {
                case PROBLEM -> NamedTextColor.RED;
                case WARNING -> NamedTextColor.GOLD;
                case NOTE -> NamedTextColor.GRAY;
            };
            String title = switch (level) {
                case PROBLEM -> "Problems";
                case WARNING -> "Warnings";
                case NOTE -> "Notes";
            };
            sender.sendMessage(Component.text(title + " (" + ofLevel.size() + ")", color));
            ofLevel.stream().limit(25).forEach(finding -> sender.sendMessage(Component.text("  " + finding.text(), color)));
            if (ofLevel.size() > 25) {
                sender.sendMessage(Component.text("  ... and " + (ofLevel.size() - 25) + " more", color));
            }
        }
    }

    private static boolean signExists(dev.advancedtrains.atcs.network.BlockPos position) {
        World world = Bukkit.getWorld(position.world());
        if (world == null || !world.isChunkLoaded(position.chunkX(), position.chunkZ())) {
            return true;
        }
        return world.getBlockAt(position.x(), position.y(), position.z()).getState() instanceof Sign;
    }

    static void undo(Support support, CommandContext context) throws CommandError {
        try {
            Network restored = support.store.undo();
            Chat.success(context.sender(), "Restored the previous network.yml: " + restored.stations().size() + " stations, "
                    + restored.lines().size() + " lines, " + restored.services().size() + " services. "
                    + support.store.history().size() + " earlier version(s) left; /atcs undo again goes further back.");
        } catch (NetworkStore.EditRejectedException exception) {
            throw new CommandError(exception.getMessage());
        } catch (IOException exception) {
            throw new CommandError("Could not restore network.yml: " + exception.getMessage());
        }
    }

    static void history(Support support, CommandContext context) throws CommandError {
        List<Path> entries;
        try {
            entries = support.store.history();
        } catch (IOException exception) {
            throw new CommandError("Could not list the history: " + exception.getMessage());
        }
        CommandSender sender = context.sender();
        sender.sendMessage(Chat.header("network.yml history"));
        if (entries.isEmpty()) {
            sender.sendMessage(Component.text("No earlier versions yet; every command edit keeps one (the last "
                    + NetworkStore.HISTORY + ").", NamedTextColor.GRAY));
            return;
        }
        sender.sendMessage(Component.text(entries.size() + " earlier version(s), newest first; /atcs undo restores the first:", NamedTextColor.GRAY));
        entries.stream().limit(10).forEach(path -> sender.sendMessage(Component.text("  " + path.getFileName(), NamedTextColor.WHITE)));
    }
}
