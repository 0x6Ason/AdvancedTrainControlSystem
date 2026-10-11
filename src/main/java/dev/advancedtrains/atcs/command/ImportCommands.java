package dev.advancedtrains.atcs.command;

import dev.advancedtrains.atcs.importer.ProposalMerger;
import dev.advancedtrains.atcs.importer.StationImporter;
import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.NetworkStore;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.operation.RouteSuggester;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

import java.io.IOException;
import java.util.List;

import static dev.advancedtrains.atcs.command.Node.group;
import static dev.advancedtrains.atcs.command.Node.leaf;
import static dev.advancedtrains.atcs.command.Support.MANAGE;

/**
 * {@code /atcs import ...}: draft stations from the signs already in the world and services from the
 * trips trains are seen making.
 */
final class ImportCommands {
    private static final int PAGE_SIZE = 10;

    private ImportCommands() {
    }

    static Node build(Support support) {
        return group("import", MANAGE, "Set up the network from existing signs and running trains",
                leaf("scan", MANAGE, "", "Read signs in loaded chunks of railway worlds (repeat in other areas)",
                        context -> scan(support, context)),
                leaf("stations", MANAGE, "[page]", "Show the stations drafted from scanned signs",
                        context -> stations(support, context)),
                leaf("services", MANAGE, "[min-trips]", "Show lines and services suggested from observed trains",
                        context -> services(support, context), (context, index) -> index == 0 ? List.of("1", "2", "3") : List.of()),
                leaf("write", MANAGE, "[min-trips]", "Write both proposals to import-proposal.yml for review",
                        context -> write(support, context)),
                leaf("apply", MANAGE, "", "Add the reviewed import-proposal.yml to the network", context -> apply(support, context)),
                leaf("reset", MANAGE, "", "Forget the scanned signs", context -> {
                    support.importer.reset();
                    Chat.info(context.sender(), "Scanned signs cleared");
                }));
    }

    private static void scan(Support support, CommandContext context) throws CommandError {
        if (support.importer.scanning()) {
            throw new CommandError("A scan is already running");
        }
        List<World> worlds = support.control.railwayWorlds();
        if (worlds.isEmpty()) {
            throw new CommandError("No loaded world is enabled in TrainCarts");
        }
        CommandSender sender = context.sender();
        Chat.info(sender, "Scanning loaded chunks of " + String.join(", ", worlds.stream().map(World::getName).toList()) + "...");
        support.importer.scan(worlds, result -> {
            StationImporter.Proposal proposal = support.importer.stations();
            Chat.success(sender, "Found " + result.signs().size() + " signs in " + result.chunksScanned()
                    + " chunks; " + support.importer.collectedSigns() + " collected in total.");
            if (proposal != null) {
                sender.sendMessage(Component.text()
                        .append(Component.text("Draft: " + proposal.stations().size() + " stations with "
                                + proposal.platformCount() + " platforms. ", NamedTextColor.GRAY))
                        .append(Chat.run("[show]", "/atcs import stations"))
                        .append(Component.text(" ", NamedTextColor.GRAY))
                        .append(Chat.run("[write file]", "/atcs import write"))
                        .build());
            }
            Chat.info(sender, "Only loaded chunks are read. Visit other parts of the network and scan again to add them.");
        });
    }

    private static void stations(Support support, CommandContext context) throws CommandError {
        StationImporter.Proposal proposal = requireStations(support);
        List<StationImporter.ProposedStation> stations = proposal.stations();
        int pages = Math.max(1, (stations.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = StationCommands.parsePage(context.argOr(0, "1"), pages);
        CommandSender sender = context.sender();
        sender.sendMessage(Chat.header("Drafted stations, page " + page + "/" + pages));
        for (StationImporter.ProposedStation station : stations.subList((page - 1) * PAGE_SIZE,
                Math.min(stations.size(), page * PAGE_SIZE))) {
            sender.sendMessage(Component.text()
                    .append(Component.text(station.id(), NamedTextColor.AQUA))
                    .append(Component.text(" " + station.name(), NamedTextColor.WHITE))
                    .append(Component.text(" +" + station.platforms().size() + " platform(s)"
                            + (station.existing() ? " to existing station" : ""), NamedTextColor.GRAY))
                    .build());
        }
        if (!proposal.notes().isEmpty()) {
            Chat.warn(sender, proposal.notes().size() + " note(s), e.g. " + proposal.notes().get(0)
                    + ". All notes are listed in the proposal file.");
        }
        if (page < pages) {
            sender.sendMessage(Chat.run("[next page]", "/atcs import stations " + (page + 1)));
        }
    }

    private static StationImporter.Proposal requireStations(Support support) throws CommandError {
        if (support.importer.scanning()) {
            throw new CommandError("The scan is still running; try again in a moment");
        }
        StationImporter.Proposal proposal = support.importer.stations();
        if (proposal == null) {
            throw new CommandError("Nothing scanned yet; run /atcs import scan first");
        }
        if (support.importer.collectedSigns() == 0) {
            throw new CommandError("No signs were found in the loaded chunks. Go near the railway and scan again.");
        }
        return proposal;
    }

    private static int minTrips(CommandContext context, int index) throws CommandError {
        try {
            int value = Integer.parseInt(context.argOr(index, "2"));
            if (value < 1) {
                throw new CommandError("min-trips must be at least 1");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new CommandError("'" + context.argOr(index, "") + "' is not a number");
        }
    }

    private static void services(Support support, CommandContext context) throws CommandError {
        Network network = support.network();
        if (network.triggers().isEmpty()) {
            throw new CommandError("Import or bind stations first: services are learned from trains visiting platforms");
        }
        RouteSuggester.Suggestion suggestion = support.importer.services(minTrips(context, 0));
        CommandSender sender = context.sender();
        sender.sendMessage(Chat.header("Suggested services from " + suggestion.tripsAnalysed() + " train trips"));
        if (suggestion.lines().isEmpty()) {
            Chat.info(sender, suggestion.alreadyDefined() > 0
                    ? "Every observed route is already a service."
                    : "No repeated routes seen yet. Let trains run for a while (every station visit counts), then try again.");
            return;
        }
        for (Line line : suggestion.lines()) {
            sender.sendMessage(Component.text(line.name() + " (" + line.id() + ")", TextColor.color(line.color())));
            for (Service service : line.services().values()) {
                String route = String.join(" > ", service.stops().stream()
                        .map(stop -> network.stationName(stop.stationId())).toList());
                sender.sendMessage(Component.text()
                        .append(Component.text("  " + service.name() + ": ", NamedTextColor.WHITE))
                        .append(Component.text(route, NamedTextColor.GRAY))
                        .append(Component.text(" (seen " + suggestion.observations().getOrDefault(service.key(), 0) + "x"
                                + (service.spawnSigns().isEmpty() ? "" : ", " + service.spawnSigns().size() + " spawn sign(s)")
                                + (service.continuesAs() == null ? "" : ", then " + service.continuesAs().serviceId())
                                + ")", NamedTextColor.DARK_GRAY))
                        .build());
            }
        }
        sender.sendMessage(Component.text().append(Component.text("Write them to a file to review: ", NamedTextColor.GRAY))
                .append(Chat.run("/atcs import write", "/atcs import write")).build());
    }

    private static void write(Support support, CommandContext context) throws CommandError {
        StationImporter.Proposal stations = support.importer.scanning() ? null : support.importer.stations();
        RouteSuggester.Suggestion services = support.network().triggers().isEmpty()
                ? null : support.importer.services(minTrips(context, 0));
        boolean anyStations = stations != null && !stations.stations().isEmpty();
        boolean anyServices = services != null && !services.lines().isEmpty();
        if (!anyStations && !anyServices) {
            throw new CommandError("Nothing to propose yet. Run /atcs import scan to draft stations; once stations exist,"
                    + " let trains run and services will be suggested.");
        }
        try {
            support.importer.writeProposal(anyStations ? stations : null, anyServices ? services : null);
        } catch (IOException exception) {
            throw new CommandError("Could not write the proposal: " + exception.getMessage());
        }
        Chat.success(context.sender(), "Wrote " + (anyStations ? stations.stations().size() : 0) + " stations and "
                + (anyServices ? services.serviceCount() : 0) + " services to " + support.importer.proposalFile()
                + ". Review and edit it, then run /atcs import apply.");
    }

    private static void apply(Support support, CommandContext context) throws CommandError {
        try {
            ProposalMerger.Result result = support.importer.apply();
            Chat.success(context.sender(), "Added " + result.stationsAdded() + " stations, " + result.platformsAdded()
                    + " platforms, " + result.triggersAdded() + " triggers, " + result.linesAdded() + " lines and "
                    + result.servicesAdded() + " services.");
            if (!result.skipped().isEmpty()) {
                Chat.warn(context.sender(), result.skipped().size() + " item(s) were skipped, e.g. " + result.skipped().get(0));
            }
            if (result.servicesAdded() == 0) {
                Chat.info(context.sender(), "Next: let trains run, then /atcs import services suggests lines and services"
                        + " from what they do. You can also create them with /atcs line create and /atcs service create.");
            }
        } catch (IllegalArgumentException | NetworkStore.EditRejectedException exception) {
            throw new CommandError(exception.getMessage());
        } catch (IOException exception) {
            throw new CommandError("Could not apply the proposal: " + exception.getMessage());
        }
    }
}
