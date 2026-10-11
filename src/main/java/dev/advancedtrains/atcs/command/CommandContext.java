package dev.advancedtrains.atcs.command;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;

/** The sender and remaining arguments of a command being run or completed. */
public final class CommandContext {
    private final CommandSender sender;
    private final String path;
    private final String[] args;

    CommandContext(CommandSender sender, String path, String[] args) {
        this.sender = sender;
        this.path = path;
        this.args = args;
    }

    public CommandSender sender() {
        return sender;
    }

    /** The command typed so far, for example {@code /atcs station}. */
    public String path() {
        return path;
    }

    public int size() {
        return args.length;
    }

    public String arg(int index) throws CommandError {
        if (index >= args.length) {
            throw new CommandError("Missing argument " + (index + 1));
        }
        return args[index];
    }

    public String argOr(int index, String fallback) {
        return index < args.length ? args[index] : fallback;
    }

    /** Arguments from {@code index} to the end joined with spaces, for names containing spaces. */
    public String rest(int index) throws CommandError {
        if (index >= args.length) {
            throw new CommandError("Missing argument " + (index + 1));
        }
        return String.join(" ", Arrays.copyOfRange(args, index, args.length)).strip();
    }

    public String restOr(int index, String fallback) {
        return index < args.length ? String.join(" ", Arrays.copyOfRange(args, index, args.length)).strip() : fallback;
    }

    public String[] args() {
        return args.clone();
    }

    public Player player() throws CommandError {
        if (sender instanceof Player player) {
            return player;
        }
        throw new CommandError("This command must be run by a player in game");
    }

    CommandContext descend(String child) {
        return new CommandContext(sender, path + " " + child, Arrays.copyOfRange(args, 1, args.length));
    }
}
