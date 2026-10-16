package dev.advancedtrains.atcs.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static dev.advancedtrains.atcs.command.Node.group;
import static dev.advancedtrains.atcs.command.Node.leaf;
import static dev.advancedtrains.atcs.command.Support.MANAGE;
import static dev.advancedtrains.atcs.command.Support.USE;

/** The {@code /atcs} command tree. */
public final class AtcsCommand implements TabExecutor {
    private final Node.Group root;
    private final Logger logger;

    public AtcsCommand(Support support, Logger logger) {
        this.logger = logger;
        this.root = group("atcs", USE, "AdvancedTrainControlSystem",
                leaf("status", MANAGE, "", "Show plugin, network and integration status", context -> {
                    context.sender().sendMessage(Chat.header("ATCS status"));
                    support.control.statusLines().forEach(line ->
                            context.sender().sendMessage(Component.text(line, NamedTextColor.GRAY)));
                }),
                leaf("reload", MANAGE, "", "Reload config.yml, messages.yml and network.yml", context -> {
                    List<String> problems = support.control.reload();
                    if (problems.isEmpty()) {
                        Chat.success(context.sender(), "Reloaded");
                    } else {
                        Chat.warn(context.sender(), "Reloaded with " + problems.size() + " problem(s):");
                        problems.stream().limit(10).forEach(problem -> Chat.warn(context.sender(), " - " + problem));
                    }
                }),
                leaf("lint", MANAGE, "", "Check the network for what will not work or not show, with what to do about it",
                        context -> MaintenanceCommands.lint(support, context)),
                leaf("undo", MANAGE, "", "Restore network.yml as it was before the last command edit",
                        context -> MaintenanceCommands.undo(support, context)),
                leaf("history", MANAGE, "", "List the earlier versions of network.yml that /atcs undo can restore",
                        context -> MaintenanceCommands.history(support, context)),
                leaf("debug", MANAGE, "[on|off]", "Log every platform signal and journey event to the console", context -> {
                    boolean enabled = context.size() == 0 ? !support.control.debug() : context.arg(0).equalsIgnoreCase("on");
                    support.control.setDebug(enabled);
                    Chat.info(context.sender(), "Debug logging " + (enabled ? "on" : "off"));
                }, (context, index) -> index == 0 ? List.of("on", "off") : List.of()),
                StationCommands.build(support),
                LineCommands.build(support),
                ServiceCommands.build(support),
                TrainCommands.train(support),
                TrainCommands.trains(support),
                TrainCommands.departures(support),
                TransferCommands.build(support),
                BoardCommands.build(support),
                ImportCommands.build(support),
                FareCommands.build(support),
                TimingCommands.build(support));
    }

    /** The whole tree, for generated documentation. */
    Node.Group root() {
        return root;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        CommandContext context = new CommandContext(sender, "/" + label, args);
        if (!root.permitted(context)) {
            Chat.error(sender, "You do not have permission to use ATCS commands");
            return true;
        }
        try {
            root.execute(context);
        } catch (CommandError error) {
            Chat.error(sender, error.getMessage());
        } catch (RuntimeException exception) {
            logger.log(Level.SEVERE, "ATCS command failed: /" + label + " " + String.join(" ", args), exception);
            Chat.error(sender, "The command failed unexpectedly; see the server log");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        CommandContext context = new CommandContext(sender, "/" + alias, args);
        if (!root.permitted(context)) {
            return List.of();
        }
        try {
            return root.complete(context);
        } catch (RuntimeException exception) {
            return List.of();
        }
    }
}
