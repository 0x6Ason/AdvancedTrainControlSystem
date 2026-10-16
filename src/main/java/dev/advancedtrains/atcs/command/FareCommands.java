package dev.advancedtrains.atcs.command;

import dev.advancedtrains.atcs.fare.FareCalculator;
import dev.advancedtrains.atcs.fare.FareMatrix;
import dev.advancedtrains.atcs.fare.FareTable;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Station;
import dev.advancedtrains.atcs.util.Files2;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

import static dev.advancedtrains.atcs.command.Node.group;
import static dev.advancedtrains.atcs.command.Node.leaf;
import static dev.advancedtrains.atcs.command.Support.MANAGE;
import static dev.advancedtrains.atcs.command.Support.USE;

/** {@code /atcs fare ...} */
final class FareCommands {
    private FareCommands() {
    }

    static Node build(Support support) {
        Node.Completer stations = (context, index) -> index <= 1 ? support.stationIds() : List.of();
        return group("fare", USE, "Fares between stations: explicit pairs, zones, and a formula over the route",
                leaf("check", USE, "<from> <to>", "The fare between two stations and how it was worked out",
                        context -> check(support, context), stations),
                leaf("info", USE, "", "Currency, formula, zones and explicit pairs", context -> info(support, context)),
                leaf("generate", MANAGE, "", "Write the fare of every pair of stations as a draft (fare-matrix.yml) to correct by hand",
                        context -> generate(support, context)),
                leaf("apply", MANAGE, "[confirm]", "Load the corrected draft into fares.yml; your changes are kept from then on",
                        context -> apply(support, context), (context, index) -> index == 0 ? List.of("confirm") : List.of()),
                leaf("set", MANAGE, "<from> <to> <fare|auto>", "Set the fare between two stations by hand, or let zones and formula decide again",
                        context -> set(support, context), (context, index) -> index <= 1 ? support.stationIds() : List.of("auto")),
                leaf("zone", MANAGE, "<station> <zone|none>", "Put a station in a zone",
                        context -> zone(support, context), (context, index) -> index == 0 ? support.stationIds()
                                : index == 1 ? zoneNames(support) : List.of()),
                leaf("zonefare", MANAGE, "<zone> <zone> <fare|none>", "The fare between two zones (a zone with itself too)",
                        context -> zoneFare(support, context), (context, index) -> index <= 1 ? zoneNames(support) : List.of("none")),
                leaf("formula", MANAGE, "<flat|per-stop|per-change|round-to|minimum> <value>",
                        "Set a part of the formula; distance bands are edited in fares.yml",
                        context -> formula(support, context), (context, index) -> index == 0
                                ? List.of("flat", "per-stop", "per-change", "round-to", "minimum") : List.of()),
                leaf("currency", MANAGE, "<symbol>", "The symbol shown with fares, for example $", context -> {
                    String symbol = context.arg(0);
                    edit(support, context, table -> table.withCurrency(symbol), "Fares are now shown as " + symbol);
                }));
    }

    private static List<String> zoneNames(Support support) {
        return List.copyOf(new TreeSet<>(support.fares.store().table().zones().values()));
    }

    private static void check(Support support, CommandContext context) throws CommandError {
        Station from = support.station(context.arg(0));
        Station to = support.station(context.arg(1));
        FareCalculator calculator = support.fares.calculator();
        FareTable table = calculator.table();
        Optional<FareCalculator.Quote> quote = calculator.quote(from.id(), to.id());
        CommandSender sender = context.sender();
        if (quote.isEmpty()) {
            throw new CommandError("No fare could be worked out between " + from.name() + " and " + to.name());
        }
        sender.sendMessage(Chat.header(from.name() + " to " + to.name() + ": " + table.format(quote.get().fare())));
        sender.sendMessage(Chat.field("Layer", switch (quote.get().layer()) {
            case PAIR -> "explicit pair";
            case ZONE -> "zone fare";
            case FORMULA -> "formula";
            case NONE -> "none";
        }));
        quote.get().reasoning().forEach(line -> sender.sendMessage(Component.text("  " + line, NamedTextColor.GRAY)));
        if (!table.configured()) {
            sender.sendMessage(Component.text("No fares are configured yet: edit fares.yml or use /atcs fare formula, zone and set.",
                    NamedTextColor.GOLD));
        }
    }

    private static void info(Support support, CommandContext context) {
        FareTable table = support.fares.store().table();
        CommandSender sender = context.sender();
        sender.sendMessage(Chat.header("Fares"));
        sender.sendMessage(Chat.field("Currency", table.currency()));
        FareTable.Formula formula = table.formula();
        sender.sendMessage(Chat.field("Formula", "flat " + table.format(formula.flat()) + ", per stop " + table.format(formula.perStop())
                + ", per change " + table.format(formula.perChange()) + ", " + formula.bands().size() + " distance band(s), rounded to "
                + (formula.roundTo() > 0 ? table.format(formula.roundTo()) : "nothing") + ", minimum " + table.format(formula.minimum())));
        sender.sendMessage(Chat.field("Zones", table.zones().isEmpty() ? "none" : table.zones().size() + " stations in "
                + new TreeSet<>(table.zones().values()).size() + " zones, " + table.zoneFares().size() + " zone fares"));
        sender.sendMessage(Chat.field("Explicit pairs", table.pairs().size() + " (" + table.keptPairs() + " set by hand)"));
        Path draft = support.fares.store().file().resolveSibling(FareMatrix.DRAFT_FILE);
        sender.sendMessage(Chat.field("Draft", Files.exists(draft) ? draft.getFileName() + " exists; edit it and run /atcs fare apply" : "none; /atcs fare generate writes one"));
        if (!support.fares.store().loadErrors().isEmpty()) {
            sender.sendMessage(Component.text("fares.yml has errors: " + support.fares.store().loadErrors().get(0), NamedTextColor.RED));
        }
    }

    private static void generate(Support support, CommandContext context) throws CommandError {
        Network network = support.network();
        if (network.stations().size() < 2) {
            throw new CommandError("The network needs at least two stations");
        }
        FareCalculator calculator = support.fares.calculator();
        FareMatrix.Draft draft = FareMatrix.generate(network, calculator);
        Path file = support.fares.store().file().resolveSibling(FareMatrix.DRAFT_FILE);
        try {
            Files2.writeAtomically(file, FareMatrix.writeDraft(draft, network, calculator.table(), Instant.now()));
        } catch (IOException exception) {
            throw new CommandError("Could not write " + file.getFileName() + ": " + exception.getMessage());
        }
        Chat.success(context.sender(), "Wrote " + draft.pairs().size() + " fares to " + file.getFileName() + " ("
                + calculator.table().keptPairs() + " set by hand kept). Correct the wrong ones, then run /atcs fare apply.");
    }

    private static void apply(Support support, CommandContext context) throws CommandError {
        Path file = support.fares.store().file().resolveSibling(FareMatrix.DRAFT_FILE);
        if (!Files.exists(file)) {
            throw new CommandError("There is no " + file.getFileName() + "; run /atcs fare generate first");
        }
        FareMatrix.Draft draft;
        try {
            draft = FareMatrix.readDraft(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException exception) {
            throw new CommandError("Could not read " + file.getFileName() + ": " + exception.getMessage());
        }
        Map<String, Double> edited = draft.edited();
        Network network = support.network();
        CommandSender sender = context.sender();
        if (!context.argOr(0, "").equalsIgnoreCase("confirm")) {
            sender.sendMessage(Chat.header("Fare draft"));
            sender.sendMessage(Component.text(draft.pairs().size() + " pairs, " + edited.size() + " changed by hand:", NamedTextColor.GRAY));
            FareTable table = support.fares.store().table();
            edited.entrySet().stream().limit(20).forEach(entry -> {
                List<String> ids = FareTable.stationsOf(entry.getKey());
                sender.sendMessage(Component.text("  " + network.stationName(ids.get(0)) + " - " + network.stationName(ids.get(1)) + ": "
                        + table.format(entry.getValue()) + " (was " + table.format(draft.generated().getOrDefault(entry.getKey(), 0.0)) + ")",
                        NamedTextColor.WHITE));
            });
            sender.sendMessage(Component.text()
                    .append(Component.text("Nothing changed yet. ", NamedTextColor.GRAY))
                    .append(Chat.run("Run /atcs fare apply confirm", "/atcs fare apply confirm"))
                    .append(Component.text(" to load the draft.", NamedTextColor.GRAY)).build());
            return;
        }
        edit(support, context, table -> FareMatrix.apply(table, draft),
                "Loaded " + draft.pairs().size() + " fares; " + edited.size() + " set by hand and kept from now on");
    }

    private static void set(Support support, CommandContext context) throws CommandError {
        Station from = support.station(context.arg(0));
        Station to = support.station(context.arg(1));
        if (from.id().equals(to.id())) {
            throw new CommandError("Give two different stations");
        }
        String value = context.arg(2);
        if (value.equalsIgnoreCase("auto")) {
            edit(support, context, table -> table.withPair(from.id(), to.id(), null),
                    "Zones and formula decide the fare between " + from.name() + " and " + to.name() + " again");
            return;
        }
        double fare = fare(value);
        edit(support, context, table -> table.withPair(from.id(), to.id(), new FareTable.Pair(fare, true)),
                from.name() + " to " + to.name() + " now costs " + support.fares.store().table().format(fare) + " (set by hand)");
    }

    private static void zone(Support support, CommandContext context) throws CommandError {
        Station station = support.station(context.arg(0));
        String zone = context.arg(1);
        if (zone.equalsIgnoreCase("none")) {
            edit(support, context, table -> table.withZone(station.id(), null), station.name() + " is in no zone");
            return;
        }
        edit(support, context, table -> table.withZone(station.id(), zone), station.name() + " is in zone " + zone.toLowerCase(Locale.ROOT));
    }

    private static void zoneFare(Support support, CommandContext context) throws CommandError {
        String a = context.arg(0);
        String b = context.arg(1);
        String value = context.arg(2);
        if (value.equalsIgnoreCase("none")) {
            edit(support, context, table -> table.withZoneFare(a, b, null), "No zone fare between " + a + " and " + b);
            return;
        }
        double fare = fare(value);
        edit(support, context, table -> table.withZoneFare(a, b, fare),
                "Zone " + a + " to zone " + b + " now costs " + support.fares.store().table().format(fare));
    }

    private static void formula(Support support, CommandContext context) throws CommandError {
        String part = context.arg(0).toLowerCase(Locale.ROOT);
        double value = fare(context.arg(1));
        UnaryOperator<FareTable.Formula> change = switch (part) {
            case "flat" -> f -> new FareTable.Formula(value, f.perStop(), f.perChange(), f.bands(), f.roundTo(), f.minimum());
            case "per-stop" -> f -> new FareTable.Formula(f.flat(), value, f.perChange(), f.bands(), f.roundTo(), f.minimum());
            case "per-change" -> f -> new FareTable.Formula(f.flat(), f.perStop(), value, f.bands(), f.roundTo(), f.minimum());
            case "round-to" -> f -> new FareTable.Formula(f.flat(), f.perStop(), f.perChange(), f.bands(), value, f.minimum());
            case "minimum" -> f -> new FareTable.Formula(f.flat(), f.perStop(), f.perChange(), f.bands(), f.roundTo(), value);
            default -> throw new CommandError("The part must be flat, per-stop, per-change, round-to or minimum");
        };
        edit(support, context, table -> table.withFormula(change.apply(table.formula())), "Formula: " + part + " is now " + value);
    }

    private static double fare(String text) throws CommandError {
        try {
            double value = Double.parseDouble(text);
            if (value < 0 || Double.isNaN(value) || Double.isInfinite(value)) {
                throw new CommandError("A fare cannot be negative");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new CommandError("'" + text + "' is not a number");
        }
    }

    private static void edit(Support support, CommandContext context, UnaryOperator<FareTable> change, String done) throws CommandError {
        try {
            support.fares.store().edit(change);
        } catch (IOException exception) {
            throw new CommandError(exception.getMessage());
        }
        support.runtime.fareTableChanged();
        Chat.success(context.sender(), done);
    }
}
