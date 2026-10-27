package dev.advancedtrains.atcs.command;

import dev.advancedtrains.atcs.fare.BillingLedger;
import dev.advancedtrains.atcs.fare.FareCalculator;
import dev.advancedtrains.atcs.fare.FareMatrix;
import dev.advancedtrains.atcs.fare.FareTable;
import dev.advancedtrains.atcs.fare.RideBilling;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Station;
import dev.advancedtrains.atcs.runtime.FareRides;
import dev.advancedtrains.atcs.util.Files2;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

import static dev.advancedtrains.atcs.command.Node.group;
import static dev.advancedtrains.atcs.command.Node.leaf;
import static dev.advancedtrains.atcs.command.Support.MANAGE;
import static dev.advancedtrains.atcs.command.Support.USE;

/** {@code /atcs fare ...} */
final class FareCommands {
    private static final int RECEIPTS_SHOWN = 10;

    private FareCommands() {
    }

    static Node build(Support support) {
        Node.Completer stations = (context, index) -> index <= 1 ? support.stationIds() : List.of();
        return group("fare", USE, "Fares between stations: explicit pairs, zones, and a formula over the route",
                leaf("check", USE, "<from> <to>", "The fare between two stations and how it was worked out",
                        context -> check(support, context), stations),
                leaf("info", USE, "", "Currency, pricing rule, formula, zones and station pairs", context -> info(support, context)),
                leaf("pricing", MANAGE, "<legacy|cumulative|highest|lowest|custom> [confirm]", "Choose how fare components combine for new journeys",
                        context -> pricing(support, context), (context, index) -> index == 0
                                ? List.of("legacy", "cumulative", "highest", "lowest", "custom") : List.of("confirm")),
                leaf("calculation", MANAGE, "<expression>", "Set a custom fare rule, for example max(formula, zones) + pairs",
                        context -> {
                            String expression = context.rest(0);
                            FareTable current = support.fares.store().table();
                            if (current.pricing() == FareTable.Pricing.LEGACY && !current.pairs().isEmpty()) {
                                throw new CommandError("Review the existing pair fares, then /atcs fare pricing custom confirm before setting a calculation");
                            }
                            try {
                                edit(support, context, table -> table.withCalculation(expression), "Custom fare rule: " + expression);
                            } catch (IllegalArgumentException exception) {
                                throw new CommandError(exception.getMessage());
                            }
                        }),
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
                }),
                leaf("billing", MANAGE, "[on|off]", "Charge passengers on the rides they take, through Vault, without gates",
                        context -> billing(support, context), (context, index) -> index == 0 ? List.of("on", "off") : List.of()),
                leaf("free", MANAGE, "<line> <on|off>", "Make a line free to ride; it costs nothing as part of any journey",
                        context -> free(support, context),
                        (context, index) -> index == 0 ? support.lineIds() : index == 1 ? List.of("on", "off") : List.of()),
                leaf("minimum", MANAGE, "<fare>", "The least a paid journey costs, even one that ends where it began", context -> {
                    double value = fare(context.arg(0));
                    editBilling(support, context, b -> new FareTable.Billing(b.enabled(), b.freeLines(), value,
                            b.transferWindowSeconds(), b.transferDiscount(), b.transferDiscountPercent()),
                            "A paid journey now costs at least " + support.fares.store().table().format(value));
                }),
                leaf("transfer", MANAGE, "<seconds> [discount|percent%|none]",
                        "How long a change of trains may take, and what changing to another paid line takes off",
                        context -> transfer(support, context),
                        (context, index) -> index == 0 ? List.of("180") : index == 1 ? List.of("none", "50%") : List.of()),
                leaf("receipts", USE, "[player]", "Recent fares and the journey in progress; staff can name a player",
                        context -> receipts(support, context)),
                leaf("receipt", USE, "<receipt> [page]", "The saved price breakdown and actual route; staff can inspect any receipt",
                        context -> receipt(support, context)),
                leaf("endtrip", USE, "[player]", "End your journey now and pay, instead of when the transfer window closes",
                        context -> endTrip(support, context)),
                leaf("resolve", MANAGE, "<receipt> <paid|waive|retry>",
                        "Settle a receipt by hand after checking the player's balance: paid, forgiven, or collect again",
                        context -> resolve(support, context), (context, index) -> index == 0
                                ? outstandingReceipts(support) : index == 1 ? List.of("paid", "waive", "retry") : List.of()));
    }

    private static void pricing(Support support, CommandContext context) throws CommandError {
        FareTable.Pricing mode;
        try {
            mode = FareTable.Pricing.valueOf(context.arg(0).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new CommandError("Give legacy, cumulative, highest, lowest or custom");
        }
        FareTable current = support.fares.store().table();
        if (current.pricing() == FareTable.Pricing.LEGACY && mode != FareTable.Pricing.LEGACY && !current.pairs().isEmpty()
                && !context.argOr(1, "").equalsIgnoreCase("confirm")) {
            throw new CommandError("Existing station pairs will become per-hop supplements. Remove any generated total-fare matrix first, "
                    + "or repeat with confirm if these are the supplements you want");
        }
        edit(support, context, table -> table.withPricing(mode), "Pricing is " + mode.name().toLowerCase(Locale.ROOT)
                + " for new journeys; journeys in progress keep their original rule");
    }

    private static void receipt(Support support, CommandContext context) throws CommandError {
        BillingLedger.Charge charge = rides(support).billing().charge(context.arg(0).toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new CommandError("No saved receipt with that number"));
        CommandSender sender = context.sender();
        if (!sender.hasPermission(MANAGE) && !(sender instanceof Player player && player.getUniqueId().equals(charge.player()))) {
            throw new CommandError("You can only see your own fares");
        }
        int page;
        try {
            page = Integer.parseInt(context.argOr(1, "1"));
        } catch (NumberFormatException exception) {
            throw new CommandError("Give a page number");
        }
        int pages = Math.max(1, (charge.details().size() + 11) / 12);
        if (page < 1 || page > pages) {
            throw new CommandError("Give a page from 1 to " + pages);
        }
        sender.sendMessage(Chat.header("Receipt " + charge.id() + ": " + FareTable.EMPTY.withCurrency(charge.currency()).format(charge.amount())
                + " " + status(charge.status()) + " (" + page + "/" + pages + ")"));
        if (charge.details().isEmpty()) {
            Chat.info(sender, "This older receipt has no saved breakdown");
        }
        charge.details().stream().skip((page - 1L) * 12).limit(12)
                .forEach(line -> sender.sendMessage(Component.text(line, NamedTextColor.GRAY)));
        if (page < pages) {
            sender.sendMessage(Chat.run("Next page", "/atcs fare receipt " + charge.id() + " " + (page + 1)));
        }
    }

    private static void billing(Support support, CommandContext context) throws CommandError {
        if (context.size() > 0) {
            boolean on = onOff(context.arg(0));
            editBilling(support, context, b -> new FareTable.Billing(on, b.freeLines(), b.minimum(),
                    b.transferWindowSeconds(), b.transferDiscount(), b.transferDiscountPercent()),
                    on ? "Automatic fares are on: passengers are charged when their journey ends" : "Automatic fares are off");
            if (on) {
                FareRides.gatePlugins(context.sender().getServer()).forEach(plugin -> Chat.warn(context.sender(), plugin
                        + " is installed: passengers who also pass its gates are charged twice. Remove the gates."));
            }
            return;
        }
        FareTable table = support.fares.store().table();
        FareTable.Billing billing = table.billing();
        FareRides rides = support.fares.rides();
        CommandSender sender = context.sender();
        sender.sendMessage(Chat.header("Automatic fares"));
        sender.sendMessage(Chat.field("Billing", billing.enabled() ? "on" : "off"));
        String economy = rides == null ? null : rides.payment().economyName();
        String stopped = rides == null ? "the fare ledger could not be read; see the server log"
                : !rides.billing().healthy() ? "the fare ledger could not be written; fix it and restart the server"
                : !billing.enabled() ? null
                : !support.fares.store().loadErrors().isEmpty() ? "fares.yml has errors; see /atcs fare info"
                : economy == null ? "no economy: install Vault and an economy plugin such as EssentialsX" : null;
        if (stopped != null) {
            sender.sendMessage(Component.text("Not charging: " + stopped, NamedTextColor.RED));
        }
        sender.sendMessage(Chat.field("Economy", economy == null ? "none" : economy));
        sender.sendMessage(Chat.field("Free lines", billing.freeLines().isEmpty() ? "none" : String.join(", ", billing.freeLines())));
        sender.sendMessage(Chat.field("Minimum", table.format(Math.max(billing.minimum(), table.formula().minimum()))));
        sender.sendMessage(Chat.field("Transfers", "within " + billing.transferWindowSeconds() + "s, " + discount(table)));
        if (rides != null) {
            List<BillingLedger.Ride> open = rides.billing().rides();
            List<BillingLedger.Charge> outstanding = rides.billing().charges().stream().filter(BillingLedger.Charge::outstanding).toList();
            long review = outstanding.stream().filter(charge -> charge.status() != BillingLedger.Status.DUE).count();
            sender.sendMessage(Chat.field("Journeys", open.size() + " in progress, "
                    + open.stream().filter(ride -> ride.train() != null).count() + " on a train"));
            sender.sendMessage(Chat.field("Unpaid", outstanding.size() + " receipt(s)" + (review > 0 ? ", " + review + " to check" : "")));
            if (review > 0) {
                sender.sendMessage(Component.text()
                        .append(Component.text("Payments could not be confirmed. ", NamedTextColor.GOLD))
                        .append(Chat.run("Show them", "/atcs fare receipts")).build());
            }
        }
        if (billing.enabled()) {
            FareRides.gatePlugins(sender.getServer()).forEach(plugin -> Chat.warn(sender, plugin
                    + " is installed: passengers who also pass its gates are charged twice. Remove the gates."));
        }
    }

    private static String discount(FareTable table) {
        FareTable.Billing billing = table.billing();
        List<String> parts = new ArrayList<>();
        if (billing.transferDiscountPercent() > 0) {
            parts.add(BigDecimal.valueOf(billing.transferDiscountPercent()).stripTrailingZeros().toPlainString() + "%");
        }
        if (billing.transferDiscount() > 0) {
            parts.add(table.format(billing.transferDiscount()));
        }
        return parts.isEmpty() ? "no discount" : String.join(" and ", parts) + " off the next paid line";
    }

    private static void free(Support support, CommandContext context) throws CommandError {
        boolean on = onOff(context.arg(1));
        String line = on ? support.line(context.arg(0)).id() : Support.id("line", context.arg(0));
        editBilling(support, context, b -> {
            Set<String> lines = new LinkedHashSet<>(b.freeLines());
            if (on) {
                lines.add(line);
            } else {
                lines.remove(line);
            }
            return new FareTable.Billing(b.enabled(), lines, b.minimum(), b.transferWindowSeconds(),
                    b.transferDiscount(), b.transferDiscountPercent());
        }, on ? "Line " + line + " is free to ride" : "Line " + line + " is charged like the others");
    }

    private static void transfer(Support support, CommandContext context) throws CommandError {
        int seconds;
        try {
            seconds = Integer.parseInt(context.arg(0));
        } catch (NumberFormatException exception) {
            throw new CommandError("'" + context.arg(0) + "' is not a number of seconds");
        }
        if (seconds < 0 || seconds > 3600) {
            throw new CommandError("The transfer window must be between 0 and 3600 seconds");
        }
        FareTable.Billing current = support.fares.store().table().billing();
        double fixed = current.transferDiscount();
        double percent = current.transferDiscountPercent();
        if (context.size() > 1) {
            String value = context.arg(1);
            if (value.equalsIgnoreCase("none")) {
                fixed = 0;
                percent = 0;
            } else if (value.endsWith("%")) {
                percent = fare(value.substring(0, value.length() - 1));
                fixed = 0;
                if (percent > 100) {
                    throw new CommandError("A discount cannot be more than 100%");
                }
            } else {
                fixed = fare(value);
                percent = 0;
            }
        }
        double newFixed = fixed;
        double newPercent = percent;
        editBilling(support, context, b -> new FareTable.Billing(b.enabled(), b.freeLines(), b.minimum(), seconds, newFixed, newPercent),
                "Changes of train within " + seconds + "s continue the journey");
        Chat.info(context.sender(), "Changing to another paid line: " + discount(support.fares.store().table()));
    }

    private static void receipts(Support support, CommandContext context) throws CommandError {
        RideBilling billing = rides(support).billing();
        CommandSender sender = context.sender();
        List<BillingLedger.Charge> shown;
        if (context.size() > 0) {
            if (!sender.hasPermission(MANAGE)) {
                throw new CommandError("You can only see your own fares");
            }
            String name = context.arg(0);
            shown = billing.charges().stream().filter(charge -> charge.name().equalsIgnoreCase(name)).toList();
            sender.sendMessage(Chat.header("Fares of " + name));
        } else if (sender instanceof Player player) {
            shown = billing.charges().stream().filter(charge -> charge.player().equals(player.getUniqueId())).toList();
            sender.sendMessage(Chat.header("Your fares"));
            billing.ride(player.getUniqueId()).ifPresent(ride -> sender.sendMessage(Chat.field("Journey",
                    support.network().stationName(ride.origin()) + " to " + support.network().stationName(ride.destination())
                            + ", " + billing.tariff(ride).format(billing.fareSoFar(ride).fare()) + " so far")));
        } else {
            shown = billing.charges().stream().filter(BillingLedger.Charge::outstanding).toList();
            sender.sendMessage(Chat.header("Unpaid receipts"));
        }
        if (shown.isEmpty()) {
            sender.sendMessage(Component.text("No receipts", NamedTextColor.GRAY));
            return;
        }
        DateTimeFormatter time = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());
        Network network = support.network();
        for (BillingLedger.Charge charge : shown.subList(Math.max(0, shown.size() - RECEIPTS_SHOWN), shown.size())) {
            NamedTextColor color = switch (charge.status()) {
                case PAID, WAIVED -> NamedTextColor.GRAY;
                case DUE -> NamedTextColor.GOLD;
                case PROCESSING, UNCERTAIN -> NamedTextColor.RED;
            };
            sender.sendMessage(Component.text(charge.id() + "  " + time.format(Instant.ofEpochMilli(charge.at())) + "  "
                    + (context.size() == 0 && sender instanceof Player ? "" : charge.name() + "  ")
                    + network.stationName(charge.from()) + " to " + network.stationName(charge.to()) + "  "
                    + FareTable.EMPTY.withCurrency(charge.currency()).format(charge.amount()) + "  "
                    + status(charge.status()), color));
        }
        if (shown.size() > RECEIPTS_SHOWN) {
            sender.sendMessage(Component.text("and " + (shown.size() - RECEIPTS_SHOWN) + " older", NamedTextColor.GRAY));
        }
    }

    private static void endTrip(Support support, CommandContext context) throws CommandError {
        RideBilling billing = rides(support).billing();
        CommandSender sender = context.sender();
        BillingLedger.Ride ride;
        if (context.size() > 0) {
            if (!sender.hasPermission(MANAGE)) {
                throw new CommandError("You can only end your own journey");
            }
            String name = context.arg(0);
            ride = billing.rides().stream().filter(open -> open.name().equalsIgnoreCase(name)).findFirst()
                    .orElseThrow(() -> new CommandError(name + " has no journey in progress"));
        } else if (sender instanceof Player player) {
            ride = billing.ride(player.getUniqueId()).orElseThrow(() -> new CommandError("You have no journey in progress"));
        } else {
            throw new CommandError("Name the player whose journey to end");
        }
        boolean own = sender instanceof Player self && self.getUniqueId().equals(ride.player());
        if (ride.train() != null) {
            throw new CommandError(own ? "You are still on the train; end your journey once you get off"
                    : ride.name() + " is still on a train");
        }
        BillingLedger.Charge charge;
        try {
            charge = billing.end(ride.player(), System.currentTimeMillis())
                    .orElseThrow(() -> new CommandError("Automatic fares are off"));
        } catch (IOException exception) {
            throw new CommandError(exception.getMessage());
        }
        // The rider is told what they paid by the receipt message; free journeys have none by default.
        if (!own) {
            Network network = support.network();
            Chat.success(sender, "Ended the journey of " + ride.name() + ": " + network.stationName(charge.from()) + " to "
                    + network.stationName(charge.to()) + ", " + FareTable.EMPTY.withCurrency(charge.currency()).format(charge.amount())
                    + " " + status(charge.status()));
        } else if (charge.amount() == 0) {
            Chat.success(sender, "Journey ended; it was free");
        }
    }

    private static String status(BillingLedger.Status status) {
        return switch (status) {
            case DUE -> "unpaid";
            case PROCESSING, UNCERTAIN -> "to check";
            case PAID -> "paid";
            case WAIVED -> "waived";
        };
    }

    private static void resolve(Support support, CommandContext context) throws CommandError {
        RideBilling billing = rides(support).billing();
        String id = context.arg(0).toLowerCase(Locale.ROOT);
        BillingLedger.Status status = switch (context.arg(1).toLowerCase(Locale.ROOT)) {
            case "paid" -> BillingLedger.Status.PAID;
            case "waive" -> BillingLedger.Status.WAIVED;
            case "retry" -> BillingLedger.Status.DUE;
            default -> throw new CommandError("Give paid (the money was taken), waive (forgive it) or retry (it was not taken)");
        };
        long now = System.currentTimeMillis();
        BillingLedger.Charge charge;
        try {
            charge = billing.resolve(id, status, now);
            if (status == BillingLedger.Status.DUE) {
                billing.retry(charge.player(), now);
                charge = billing.charge(id).orElse(charge);
            }
        } catch (IllegalArgumentException | IOException exception) {
            throw new CommandError(exception.getMessage());
        }
        Chat.success(context.sender(), "Receipt " + id + " of " + charge.name() + " is " + status(charge.status()));
    }

    private static List<String> outstandingReceipts(Support support) {
        FareRides rides = support.fares.rides();
        return rides == null ? List.of() : rides.billing().charges().stream()
                .filter(BillingLedger.Charge::outstanding).map(BillingLedger.Charge::id).toList();
    }

    private static FareRides rides(Support support) throws CommandError {
        FareRides rides = support.fares.rides();
        if (rides == null) {
            throw new CommandError("The fare ledger could not be read, so automatic fares are off; see the server log");
        }
        return rides;
    }

    private static boolean onOff(String text) throws CommandError {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes" -> true;
            case "off", "false", "no" -> false;
            default -> throw new CommandError("Give on or off");
        };
    }

    private static void editBilling(Support support, CommandContext context, UnaryOperator<FareTable.Billing> change,
                                    String done) throws CommandError {
        try {
            edit(support, context, table -> table.withBilling(change.apply(table.billing())), done);
        } catch (IllegalArgumentException exception) {
            throw new CommandError(exception.getMessage());
        }
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
            case COMBINED -> table.pricing().name().toLowerCase(Locale.ROOT);
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
        sender.sendMessage(Chat.field("Pricing", table.pricing().name().toLowerCase(Locale.ROOT)));
        if (table.pricing() != FareTable.Pricing.LEGACY) {
            sender.sendMessage(Chat.field("Calculation", table.expression()));
        }
        FareTable.Formula formula = table.formula();
        sender.sendMessage(Chat.field("Formula", "flat " + table.format(formula.flat()) + ", per stop " + table.format(formula.perStop())
                + ", per change " + table.format(formula.perChange()) + ", " + formula.bands().size() + " distance band(s), rounded to "
                + (formula.roundTo() > 0 ? table.format(formula.roundTo()) : "nothing") + ", minimum " + table.format(formula.minimum())));
        sender.sendMessage(Chat.field("Zones", table.zones().isEmpty() ? "none" : table.zones().size() + " stations in "
                + new TreeSet<>(table.zones().values()).size() + " zones, " + table.zoneFares().size() + " zone fares"));
        sender.sendMessage(Chat.field("Explicit pairs", table.pairs().size() + " (" + table.keptPairs() + " set by hand)"));
        sender.sendMessage(Chat.field("Automatic fares", (table.billing().enabled() ? "on" : "off") + "; see /atcs fare billing"));
        Path draft = support.fares.store().file().resolveSibling(FareMatrix.DRAFT_FILE);
        sender.sendMessage(Chat.field("Draft", Files.exists(draft) ? draft.getFileName() + " exists; edit it and run /atcs fare apply" : "none; /atcs fare generate writes one"));
        if (!support.fares.store().loadErrors().isEmpty()) {
            sender.sendMessage(Component.text("fares.yml has errors: " + support.fares.store().loadErrors().get(0), NamedTextColor.RED));
        }
    }

    private static void generate(Support support, CommandContext context) throws CommandError {
        requireLegacyMatrix(support);
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

    private static void requireLegacyMatrix(Support support) throws CommandError {
        if (support.fares.store().table().pricing() != FareTable.Pricing.LEGACY) {
            throw new CommandError("Total-fare matrices are for legacy pricing only. Use the editor's journey preview or /atcs fare check; "
                    + "/atcs fare set adds a station-hop supplement in this mode");
        }
    }

    private static void apply(Support support, CommandContext context) throws CommandError {
        requireLegacyMatrix(support);
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
