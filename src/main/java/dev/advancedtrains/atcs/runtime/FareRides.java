package dev.advancedtrains.atcs.runtime;

import dev.advancedtrains.atcs.config.Messages;
import dev.advancedtrains.atcs.fare.BillingLedger;
import dev.advancedtrains.atcs.fare.FareCalculator;
import dev.advancedtrains.atcs.fare.FarePayment;
import dev.advancedtrains.atcs.fare.FareTable;
import dev.advancedtrains.atcs.fare.RideBilling;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.operation.JourneyEvent;
import dev.advancedtrains.atcs.operation.JourneySnapshot;
import dev.advancedtrains.atcs.operation.RouteMeasure;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Automatic fares on the server: tells {@link RideBilling} who boards and leaves which train and
 * where trains go, refuses paid boardings it would not accept, and tells passengers what they paid.
 */
public final class FareRides {
    private final Plugin plugin;
    private final RideBilling billing;
    private final FarePayment payment;
    private final TrainSource trains;
    private final Function<String, Optional<JourneySnapshot>> journeys;
    private final Supplier<Network> networks;
    private final Supplier<Messages> messages;
    private final Clock clock;
    private final Logger logger;
    private @Nullable BukkitTask task;

    /**
     * @param tariffs the fares new journeys begin with; billing must come back disabled while
     *                fares.yml has errors
     * @throws IOException when the ledger cannot be read, which leaves automatic fares off
     */
    public FareRides(Plugin plugin, BillingLedger ledger, Supplier<FareTable> tariffs, FarePayment payment,
                     TrainSource trains, Function<String, Optional<JourneySnapshot>> journeys, Supplier<Network> networks,
                     RouteMeasure measure, Supplier<Messages> messages, Clock clock) throws IOException {
        this.plugin = plugin;
        this.payment = payment;
        this.trains = trains;
        this.journeys = journeys;
        this.networks = networks;
        this.messages = messages;
        this.clock = clock;
        this.logger = plugin.getLogger();
        billing = new RideBilling(ledger, networks, tariffs, measure, payment, this::receipt, clock.millis());
    }

    /** Ticket-gate plugins that charge at their gates as well; with automatic fares on, passengers pay twice. */
    public static List<String> gatePlugins(Server server) {
        List<String> found = new ArrayList<>();
        for (Plugin plugin : server.getPluginManager().getPlugins()) {
            if (plugin.getName().toLowerCase(Locale.ROOT).contains("essencard")) {
                found.add(plugin.getName());
            }
        }
        return found;
    }

    public RideBilling billing() {
        return billing;
    }

    public FarePayment payment() {
        return payment;
    }

    public void start() {
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        run("save the fare ledger", billing::flush);
    }

    /** Whether the player may sit down on the train; they are told why when not. */
    public boolean mayBoard(TrainHandle train, Player player) {
        Service service = service(train.name()).orElse(null);
        if (service == null || billing.ride(player.getUniqueId()).map(ride -> train.name().equals(ride.train())).orElse(false)) {
            return true;
        }
        try {
            RideBilling.Refusal refusal = billing.admission(player.getUniqueId(), service.key().lineId(), clock.millis());
            if (refusal == null) {
                return true;
            }
            String key = switch (refusal.reason()) {
                case BALANCE -> "fare.refused-balance";
                case DEBT -> "fare.refused-debt";
                case REVIEW -> "fare.refused-review";
            };
            send(player, key, Placeholder.unparsed("fare", format(refusal.currency(), refusal.amount())),
                    Placeholder.unparsed("receipt", refusal.receipt() == null ? "" : refusal.receipt()));
            return false;
        } catch (IOException | RuntimeException exception) {
            logger.log(Level.SEVERE, "Automatic fares: could not check " + player.getName() + " before boarding", exception);
            return true;
        }
    }

    public void boarded(TrainHandle train, Player player) {
        JourneySnapshot journey = journeys.apply(train.name()).orElse(null);
        Service service = service(train.name()).orElse(null);
        if (journey == null || service == null) {
            return;
        }
        int index = journey.lastIndex() < service.size() ? journey.lastIndex() : -1;
        int start = index >= 0 ? index : journey.nextCallingIndex(service);
        if (start < 0) {
            return;
        }
        String station = service.stop(start).stationId();
        run("record " + player.getName() + " boarding " + train.name(), () -> {
            RideBilling.Boarding boarding = billing.board(player.getUniqueId(), player.getName(), train.name(), service,
                    index, station, clock.millis());
            if (boarding == RideBilling.Boarding.CONTINUED) {
                billing.ride(player.getUniqueId()).ifPresent(ride -> send(player, "fare.transfer",
                        Placeholder.unparsed("from", networks.get().stationName(ride.origin()))));
            }
        });
    }

    public void left(Player player) {
        billing.leave(player.getUniqueId(), clock.millis()).ifPresent(ride -> {
            int window = billing.tariff(ride).billing().transferWindowSeconds();
            try {
                FareCalculator.Quote quote = billing.fareSoFar(ride);
                if (quote.fare() > 0 && window > 0) {
                    send(player, "fare.journey-pending", Placeholder.unparsed("fare", billing.tariff(ride).format(quote.fare())),
                            Placeholder.unparsed("seconds", Integer.toString(window)),
                            Placeholder.unparsed("from", networks.get().stationName(ride.origin())),
                            Placeholder.unparsed("to", networks.get().stationName(ride.destination())));
                }
            } catch (RuntimeException exception) {
                logger.log(Level.WARNING, "Automatic fares: could not price " + player.getName() + "'s journey so far", exception);
            }
        });
    }

    /** Carries riders along with their train, before anything else may cut the handling short. */
    public void journeyEvent(String train, JourneyEvent event, Network network) {
        switch (event) {
            case JourneyEvent.Arrived arrived -> network.service(arrived.service())
                    .ifPresent(service -> billing.visit(train, service, arrived.stopIndex(), arrived.at().toEpochMilli()));
            case JourneyEvent.Passed passed when passed.stopIndex() >= 0 -> network.service(passed.service())
                    .ifPresent(service -> billing.visit(train, service, passed.stopIndex(), passed.at().toEpochMilli()));
            case JourneyEvent.ServiceChanged changed when changed.to() != null -> network.service(changed.to())
                    .ifPresent(service -> billing.serviceChanged(train, service, changed.at().toEpochMilli()));
            default -> {
            }
        }
    }

    private void tick() {
        run("charge finished journeys", () -> billing.tick(clock.millis(), ride -> {
            Player player = plugin.getServer().getPlayer(ride.player());
            return player != null && trains.trainOf(player).map(train -> train.name().equals(ride.train())).orElse(false);
        }));
    }

    private void receipt(BillingLedger.Charge charge) {
        String key = switch (charge.status()) {
            case PAID -> charge.amount() > 0 ? "fare.paid" : "fare.free";
            case DUE -> "fare.declined";
            case UNCERTAIN -> "fare.uncertain";
            default -> null;
        };
        if (charge.status() == BillingLedger.Status.UNCERTAIN) {
            logger.warning("Automatic fares: the payment of " + format(charge.currency(), charge.amount()) + " by " + charge.name()
                    + " (receipt " + charge.id() + ") could not be confirmed. Check their balance, then /atcs fare resolve "
                    + charge.id() + " paid, or retry if the money was not taken.");
        }
        Player player = plugin.getServer().getPlayer(charge.player());
        if (key == null || player == null) {
            return;
        }
        Network network = networks.get();
        send(player, key, Placeholder.unparsed("fare", format(charge.currency(), charge.amount())),
                Placeholder.unparsed("from", network.stationName(charge.from())),
                Placeholder.unparsed("to", network.stationName(charge.to())),
                Placeholder.unparsed("receipt", charge.id()));
    }

    private Optional<Service> service(String train) {
        return journeys.apply(train).flatMap(journey -> journey.service() == null
                ? Optional.empty() : networks.get().service(journey.service()));
    }

    private void send(Player player, String key, TagResolver... resolvers) {
        Messages current = messages.get();
        if (current.has(key)) {
            player.sendMessage(current.render(key, resolvers));
        }
    }

    private static String format(String currency, double amount) {
        return FareTable.EMPTY.withCurrency(currency).format(amount);
    }

    private interface Step {
        void run() throws IOException;
    }

    private void run(String what, Step step) {
        try {
            step.run();
        } catch (IOException | RuntimeException exception) {
            logger.log(Level.SEVERE, "Automatic fares: could not " + what
                    + (billing.healthy() ? "" : "; fares are off until the ledger can be written and the server restarts"), exception);
        }
    }
}
