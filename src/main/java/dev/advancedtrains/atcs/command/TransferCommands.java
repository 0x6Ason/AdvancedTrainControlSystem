package dev.advancedtrains.atcs.command;

import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Fleet;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Station;
import dev.advancedtrains.atcs.network.Transfer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static dev.advancedtrains.atcs.command.Node.group;
import static dev.advancedtrains.atcs.command.Node.leaf;
import static dev.advancedtrains.atcs.command.Support.MANAGE;
import static dev.advancedtrains.atcs.command.Support.USE;

/** {@code /atcs transfer ...}: walking transfers between nearby stations. */
final class TransferCommands {
    private TransferCommands() {
    }

    static Node build(Support support) {
        Node.Completer pair = (context, index) -> index < 2 ? support.stationIds() : List.of();
        return group("transfer", USE, "Walking transfers between nearby stations",
                leaf("add", MANAGE, "<station> <station> [walk] [outside|inside]",
                        "Let passengers change between two stations on foot; walk is m:ss or seconds, estimated when left out",
                        context -> add(support, context), (context, index) -> switch (index) {
                            case 0, 1 -> support.stationIds();
                            case 2 -> List.of("0:30", "1:00", "outside", "inside");
                            case 3 -> List.of("outside", "inside");
                            default -> List.of();
                        }),
                leaf("remove", MANAGE, "<station> <station>", "Remove a walking transfer",
                        context -> remove(support, context), pair),
                leaf("list", USE, "", "List walking transfers", context -> list(support, context)));
    }

    private static void add(Support support, CommandContext context) throws CommandError {
        Station first = support.station(context.arg(0));
        Station second = support.station(context.arg(1));
        if (first.id().equals(second.id())) {
            throw new CommandError("Name two different stations");
        }
        Integer walk = null;
        boolean outside = true;
        for (int index = 2; index < context.size(); index++) {
            String word = context.arg(index).toLowerCase(Locale.ROOT);
            switch (word) {
                case "outside", "out", "gates" -> outside = true;
                case "inside", "in", "passage" -> outside = false;
                default -> {
                    Duration duration;
                    try {
                        duration = Fleet.parseEvery(word);
                    } catch (IllegalArgumentException exception) {
                        throw new CommandError("'" + context.arg(index) + "' is not a walking time (like 0:45) or outside/inside");
                    }
                    if (duration.getSeconds() < 1 || duration.getSeconds() > Transfer.MAX_WALK_SECONDS) {
                        throw new CommandError("The walk must take between 1 second and 1 hour");
                    }
                    walk = (int) duration.getSeconds();
                }
            }
        }
        boolean estimated = false;
        if (walk == null) {
            walk = estimate(support, first, second, outside)
                    .orElseThrow(() -> new CommandError("ATCS does not know where both stations are yet. Give the walking time, for example /atcs transfer add "
                            + first.id() + " " + second.id() + " 0:45"));
            estimated = true;
        }
        Transfer transfer = new Transfer(first.id(), second.id(), walk, outside);
        boolean replacing = support.network().transfer(first.id(), second.id()).isPresent();
        support.edit(network -> network.toBuilder().transfer(transfer).build());
        Chat.success(context.sender(), (replacing ? "Updated: passengers" : "Passengers") + " can walk between " + first.name() + " and "
                + second.name() + " in " + Fleet.formatEvery(Duration.ofSeconds(walk)) + (estimated ? " (estimated from the distance)" : "")
                + (outside ? ", leaving the station through the gates. The map shows a dotted line." : ", inside the stations. The map shows a solid connector."));
    }

    /** Walking time from the distance between where ATCS knows the stations to be. */
    private static Optional<Integer> estimate(Support support, Station first, Station second, boolean outside) {
        Optional<BlockPos> from = support.runtime.locator().station(first);
        Optional<BlockPos> to = support.runtime.locator().station(second);
        if (from.isEmpty() || to.isEmpty() || !from.get().world().equals(to.get().world())) {
            return Optional.empty();
        }
        BlockPos a = from.get(), b = to.get();
        double distance = Math.sqrt(Math.pow(a.x() - b.x(), 2) + Math.pow(a.y() - b.y(), 2) + Math.pow(a.z() - b.z(), 2));
        return Optional.of(Transfer.estimateSeconds(distance, outside));
    }

    private static void remove(Support support, CommandContext context) throws CommandError {
        Station first = support.station(context.arg(0));
        Station second = support.station(context.arg(1));
        if (support.network().transfer(first.id(), second.id()).isEmpty()) {
            throw new CommandError("There is no walking transfer between " + first.name() + " and " + second.name());
        }
        support.edit(network -> network.toBuilder().removeTransfer(first.id(), second.id()).build());
        Chat.success(context.sender(), "Removed the walking transfer between " + first.name() + " and " + second.name());
    }

    private static void list(Support support, CommandContext context) {
        Network network = support.network();
        if (network.transfers().isEmpty()) {
            Chat.info(context.sender(), "No walking transfers yet. Add one with /atcs transfer add <station> <station>");
            return;
        }
        context.sender().sendMessage(Chat.header("Walking transfers (" + network.transfers().size() + ")"));
        for (Transfer transfer : network.transfers()) {
            String walk = transfer.walk() == null ? "estimated" : Fleet.formatEvery(Duration.ofSeconds(transfer.walk()));
            context.sender().sendMessage(Component.text()
                    .append(Component.text(network.stationName(transfer.a()) + " - " + network.stationName(transfer.b()), NamedTextColor.WHITE))
                    .append(Component.text("  " + walk + (transfer.outside() ? ", outside" : ", inside"), NamedTextColor.GRAY))
                    .build());
        }
    }
}
