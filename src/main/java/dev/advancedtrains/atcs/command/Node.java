package dev.advancedtrains.atcs.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A command or a group of subcommands. */
public abstract sealed class Node permits Node.Group, Node.Leaf {
    final String name;
    final String permission;
    final String usage;
    final String description;

    Node(String name, String permission, String usage, String description) {
        this.name = name;
        this.permission = permission;
        this.usage = usage;
        this.description = description;
    }

    abstract void execute(CommandContext context) throws CommandError;

    abstract List<String> complete(CommandContext context);

    boolean permitted(CommandContext context) {
        return permission.isEmpty() || context.sender().hasPermission(permission);
    }

    @FunctionalInterface
    public interface Action {
        void run(CommandContext context) throws CommandError;
    }

    @FunctionalInterface
    public interface Completer {
        /** Suggestions for the last argument; {@code index} is its position. */
        List<String> complete(CommandContext context, int index);
    }

    public static Leaf leaf(String name, String permission, String usage, String description, Action action) {
        return new Leaf(name, permission, usage, description, action, (context, index) -> List.of());
    }

    public static Leaf leaf(String name, String permission, String usage, String description,
                            Action action, Completer completer) {
        return new Leaf(name, permission, usage, description, action, completer);
    }

    public static Group group(String name, String permission, String description, Node... children) {
        return new Group(name, permission, description, children);
    }

    public static final class Leaf extends Node {
        private final Action action;
        private final Completer completer;

        Leaf(String name, String permission, String usage, String description, Action action, Completer completer) {
            super(name, permission, usage, description);
            this.action = action;
            this.completer = completer;
        }

        @Override
        void execute(CommandContext context) throws CommandError {
            action.run(context);
        }

        @Override
        List<String> complete(CommandContext context) {
            if (context.size() == 0) {
                return List.of();
            }
            return filter(completer.complete(context, context.size() - 1), context.argOr(context.size() - 1, ""));
        }
    }

    public static final class Group extends Node {
        private final Map<String, Node> children = new LinkedHashMap<>();

        Group(String name, String permission, String description, Node... nodes) {
            super(name, permission, "", description);
            for (Node node : nodes) {
                children.put(node.name, node);
            }
        }

        /** The subcommands in the order they are listed, for help and generated documentation. */
        Map<String, Node> children() {
            return children;
        }

        @Override
        void execute(CommandContext context) throws CommandError {
            if (context.size() == 0) {
                help(context);
                return;
            }
            Node child = children.get(context.arg(0).toLowerCase(Locale.ROOT));
            if (child == null || !child.permitted(context)) {
                if (child == null && context.arg(0).equalsIgnoreCase("help")) {
                    help(context);
                    return;
                }
                throw new CommandError("Unknown command '" + context.arg(0) + "'. Try " + context.path() + " help");
            }
            child.execute(context.descend(child.name));
        }

        @Override
        List<String> complete(CommandContext context) {
            if (context.size() <= 1) {
                List<String> names = new ArrayList<>();
                for (Node child : children.values()) {
                    if (child.permitted(context)) {
                        names.add(child.name);
                    }
                }
                return filter(names, context.argOr(0, ""));
            }
            Node child = children.get(context.argOr(0, "").toLowerCase(Locale.ROOT));
            return child == null || !child.permitted(context) ? List.of() : child.complete(context.descend(child.name));
        }

        void help(CommandContext context) {
            context.sender().sendMessage(Chat.header(context.path().substring(1)));
            for (Node child : children.values()) {
                if (!child.permitted(context)) {
                    continue;
                }
                String command = context.path() + " " + child.name + (child.usage.isEmpty() ? "" : " " + child.usage);
                context.sender().sendMessage(Component.text()
                        .append(Chat.suggest(command, context.path() + " " + child.name + " "))
                        .append(Component.text(" - " + child.description, NamedTextColor.GRAY))
                        .build());
            }
        }
    }

    static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).sorted().toList();
    }
}
