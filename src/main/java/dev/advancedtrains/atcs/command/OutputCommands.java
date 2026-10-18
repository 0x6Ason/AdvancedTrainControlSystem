package dev.advancedtrains.atcs.command;

import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Output;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.Powerable;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

import static dev.advancedtrains.atcs.command.Node.group;
import static dev.advancedtrains.atcs.command.Node.leaf;
import static dev.advancedtrains.atcs.command.Support.MANAGE;

/**
 * {@code /atcs output ...}: redstone lamps and levers bound by command, switched with the trains
 * the way {@code atcs indicator} signs switch their levers, without a sign.
 */
final class OutputCommands {
    private OutputCommands() {
    }

    static Node build(Support support) {
        Node.Completer modes = (context, index) -> switch (index) {
            case 0 -> {
                List<String> targets = new ArrayList<>(support.stationIds());
                targets.add("block");
                yield targets;
            }
            case 1 -> List.of("at", "approach", "after", "to", "between");
            case 2 -> support.stationIds();
            default -> List.of();
        };
        return group("output", MANAGE, "Redstone lamps and levers switched with the trains, bound by command",
                leaf("add", MANAGE, "<station[:platform]>|block <id> [at|approach|after|to <station>|between <station>] [line|line/service]",
                        "Make the lamp or lever you look at show trains at, approaching or past a station, or inside a block",
                        context -> add(support, context), modes),
                leaf("remove", MANAGE, "", "Unbind the lamp or lever you look at", context -> remove(support, context)),
                leaf("list", MANAGE, "", "List bound lamps and levers", context -> list(support, context)));
    }

    /** The lamp or lever the player looks at. */
    private static Block targetOutput(Player player) throws CommandError {
        Block block = player.getTargetBlockExact(6);
        if (block == null || !isOutput(block.getBlockData())) {
            throw new CommandError("Look at a redstone lamp or a lever within 6 blocks");
        }
        return block;
    }

    static boolean isOutput(BlockData data) {
        return data instanceof Lightable || data instanceof Powerable;
    }

    private static void add(Support support, CommandContext context) throws CommandError {
        Block block = targetOutput(context.player());
        BlockPos position = Support.position(block);
        String target = context.arg(0);
        if (target.equalsIgnoreCase("block")) {
            if (context.size() < 2) {
                throw new CommandError("'block' needs the block's name, for example: /atcs output add block tunnel-east");
            }
            target = "block " + context.arg(1);
        }
        String mode = context.restOr(target.startsWith("block ") ? 2 : 1, "");
        Output output;
        try {
            output = Output.of(position, target, mode);
        } catch (IllegalArgumentException invalid) {
            throw new CommandError(invalid.getMessage());
        }
        Network network = support.network();
        if (output.rule().mode() != dev.advancedtrains.atcs.operation.Indicator.Mode.BLOCK
                && network.station(output.rule().stationId()).isEmpty()) {
            throw new CommandError("Unknown station '" + output.rule().stationId() + "'");
        }
        support.edit(current -> current.toBuilder().output(output).build());
        Chat.success(context.sender(), kind(block.getBlockData()) + " at " + position.coordinates() + " now shows: "
                + output.rule().describe(support.network()));
    }

    private static void remove(Support support, CommandContext context) throws CommandError {
        Block block = context.player().getTargetBlockExact(6);
        if (block == null) {
            throw new CommandError("Look at the bound lamp or lever within 6 blocks");
        }
        BlockPos position = Support.position(block);
        if (support.network().output(position).isEmpty()) {
            throw new CommandError("That block is not bound; /atcs output list shows the bound ones");
        }
        support.edit(current -> current.toBuilder().removeOutput(position).build());
        Chat.success(context.sender(), "Unbound the " + kind(block.getBlockData()).toLowerCase(java.util.Locale.ROOT) + " at " + position.coordinates());
    }

    private static void list(Support support, CommandContext context) {
        Network network = support.network();
        if (network.outputs().isEmpty()) {
            Chat.info(context.sender(), "No lamps or levers bound yet. Look at one and run /atcs output add <station> approach");
            return;
        }
        context.sender().sendMessage(Chat.header("Outputs (" + network.outputs().size() + ")"));
        for (Output output : network.outputs().values()) {
            context.sender().sendMessage(Component.text()
                    .append(Component.text(output.block().coordinates(), NamedTextColor.AQUA))
                    .append(Component.text(" " + state(output.block()) + " ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(output.rule().describe(network), NamedTextColor.WHITE))
                    .build());
        }
    }

    private static String kind(BlockData data) {
        return data instanceof Lightable ? "Lamp" : data instanceof Powerable ? "Lever" : "Block";
    }

    /** What stands at the position now: a lamp, a lever, something else, or an unloaded chunk. */
    private static String state(BlockPos position) {
        World world = Bukkit.getWorld(position.world());
        if (world == null || !world.isChunkLoaded(position.chunkX(), position.chunkZ())) {
            return "(not loaded)";
        }
        BlockData data = world.getBlockAt(position.x(), position.y(), position.z()).getBlockData();
        if (data instanceof Lightable lamp) {
            return lamp.isLit() ? "(lamp, lit)" : "(lamp)";
        }
        if (data instanceof Powerable lever) {
            return lever.isPowered() ? "(lever, on)" : "(lever)";
        }
        return "(gone: " + data.getMaterial().name().toLowerCase(java.util.Locale.ROOT) + ")";
    }
}
