package dev.advancedtrains.atcs.command;

import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Board;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Station;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static dev.advancedtrains.atcs.command.Node.group;
import static dev.advancedtrains.atcs.command.Node.leaf;
import static dev.advancedtrains.atcs.command.Support.MANAGE;

/** {@code /atcs board ...}: departure boards made of ordinary signs. */
final class BoardCommands {
    private BoardCommands() {
    }

    static Node build(Support support) {
        Node.Completer boards = (context, index) -> index == 0 ? support.boardIds() : List.of();
        return group("board", MANAGE, "Departure boards on signs",
                leaf("create", MANAGE, "<id> <station> [platform...]", "Make the sign you look at a departure board",
                        context -> create(support, context), (context, index) -> switch (index) {
                            case 0 -> List.of();
                            case 1 -> support.stationIds();
                            default -> support.platformIds(context.argOr(1, ""));
                        }),
                leaf("add", MANAGE, "<id>", "Add the sign you look at as the board's next line of departures",
                        context -> add(support, context), boards),
                leaf("mirror", MANAGE, "<id> [line]", "Make the sign you look at repeat one of the board's lines (the first unless given)",
                        context -> mirror(support, context), (context, index) -> index == 0 ? support.boardIds() : List.of("1", "2", "3")),
                leaf("unbind", MANAGE, "", "Remove the sign you look at from its board", context -> unbind(support, context)),
                leaf("remove", MANAGE, "<id>", "Remove a board (its signs keep their last text)", context -> {
                    Board board = board(support, context.arg(0));
                    support.edit(network -> network.toBuilder().removeBoard(board.id()).build());
                    Chat.success(context.sender(), "Removed board " + board.id());
                }, boards),
                leaf("list", MANAGE, "", "List boards", context -> list(support, context)));
    }

    private static Board board(Support support, String id) throws CommandError {
        return support.network().board(id.toLowerCase(java.util.Locale.ROOT))
                .orElseThrow(() -> new CommandError("Unknown board '" + id + "'"));
    }

    private static BlockPos boardSign(Support support, CommandContext context) throws CommandError {
        Block block = Support.targetSign(context.player());
        BlockPos position = Support.position(block);
        Network network = support.network();
        if (StationCommands.isTrainCartsSign(block) || network.platformAt(position).isPresent()) {
            throw new CommandError("That is a TrainCarts sign; boards need ordinary signs because ATCS rewrites their text");
        }
        if (network.isBoardSign(position)) {
            throw new CommandError("That sign already belongs to a board");
        }
        return position;
    }

    private static void create(Support support, CommandContext context) throws CommandError {
        String id = Support.id("board", context.arg(0));
        Station station = support.station(context.arg(1));
        if (support.network().board(id).isPresent()) {
            throw new CommandError("Board '" + id + "' already exists");
        }
        List<String> platforms = new ArrayList<>();
        for (int index = 2; index < context.size(); index++) {
            String platform = Support.id("platform", context.arg(index));
            if (station.platform(platform).isEmpty()) {
                throw new CommandError(station.name() + " has no platform '" + platform + "'");
            }
            platforms.add(platform);
        }
        BlockPos sign = boardSign(support, context);
        support.edit(network -> network.toBuilder().board(new Board(id, station.id(), platforms, List.of(sign))).build());
        Chat.success(context.sender(), "Board " + id + " shows departures from " + station.name()
                + (platforms.isEmpty() ? "" : " platform " + String.join(", ", platforms))
                + ". Look at another sign and run /atcs board add " + id + " to show the departure after,"
                + " or /atcs board mirror " + id + " to show the same one again.");
    }

    private static void mirror(Support support, CommandContext context) throws CommandError {
        Board board = board(support, context.arg(0));
        int line = 1;
        if (context.size() > 1) {
            try {
                line = Integer.parseInt(context.arg(1));
            } catch (NumberFormatException invalid) {
                throw new CommandError("The line to repeat is a number: 1 for the next departure, 2 for the one after");
            }
            if (line < 1) {
                throw new CommandError("The line to repeat is 1 or more");
            }
        }
        BlockPos sign = boardSign(support, context);
        int finalLine = line;
        support.edit(network -> network.toBuilder().board(board.withMirror(sign, finalLine)).build());
        Chat.success(context.sender(), "The sign repeats line " + line + " of board " + board.id()
                + (line > board.signs().size() ? " (which has no sign of its own yet)" : ""));
    }

    private static void add(Support support, CommandContext context) throws CommandError {
        Board board = board(support, context.arg(0));
        BlockPos sign = boardSign(support, context);
        support.edit(network -> network.toBuilder().board(board.withSign(sign)).build());
        Chat.success(context.sender(), "Board " + board.id() + " now has " + (board.signs().size() + 1) + " signs");
    }

    private static void unbind(Support support, CommandContext context) throws CommandError {
        BlockPos position = Support.position(Support.targetSign(context.player()));
        Optional<Board> owner = support.network().boards().values().stream()
                .filter(board -> board.allSigns().contains(position)).findFirst();
        Board board = owner.orElseThrow(() -> new CommandError("That sign is not part of a board"));
        support.edit(network -> network.toBuilder().board(board.withoutSign(position)).build());
        Chat.success(context.sender(), "Removed the sign from board " + board.id());
    }

    private static void list(Support support, CommandContext context) {
        Network network = support.network();
        if (network.boards().isEmpty()) {
            Chat.info(context.sender(), "No boards yet. Look at a sign and run /atcs board create <id> <station>");
            return;
        }
        context.sender().sendMessage(Chat.header("Boards (" + network.boards().size() + ")"));
        for (Board board : network.boards().values()) {
            context.sender().sendMessage(Component.text()
                    .append(Component.text(board.id(), NamedTextColor.AQUA))
                    .append(Component.text(" " + network.stationName(board.stationId())
                            + (board.platforms().isEmpty() ? "" : " platform " + String.join(", ", board.platforms())), NamedTextColor.WHITE))
                    .append(Component.text(" (" + board.signs().size() + " signs"
                            + (board.mirrors().isEmpty() ? "" : ", " + board.mirrors().size() + " mirrored") + ")", NamedTextColor.DARK_GRAY))
                    .build());
        }
    }
}
