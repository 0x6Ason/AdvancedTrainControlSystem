package dev.advancedtrains.atcs.fare;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.operation.RouteMeasure;
import dev.advancedtrains.atcs.operation.RoutePlanner;
import dev.advancedtrains.atcs.operation.TravelTimes;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static dev.advancedtrains.atcs.fare.BillingLedger.Charge;
import static dev.advancedtrains.atcs.fare.BillingLedger.Ride;
import static dev.advancedtrains.atcs.fare.BillingLedger.Status;

/**
 * Charges passengers for the rides they take, without gates: a journey starts when a player boards a
 * train running a service, follows the stops the train reaches, and is charged once the player has
 * been off trains for the transfer window. Boarding another train within the window, at the same
 * station or one connected by a transfer, continues the journey.
 *
 * <p>Billing is active only while the ledger is healthy, billing is enabled and an economy is
 * available. Otherwise nobody is refused or tracked, and journeys in progress end without a charge.
 * A payment intent is on disk before money moves, and a debit interrupted by a crash is left for
 * staff to check instead of being tried again. Not thread-safe: call it from the server thread.
 */
public final class RideBilling {
    /** Why a paid boarding was refused. */
    public enum Reason { BALANCE, DEBT, REVIEW }

    /**
     * @param amount  the balance needed, or the amount owed
     * @param receipt the charge to settle, when there is one
     */
    public record Refusal(Reason reason, double amount, String currency, @Nullable String receipt) {
    }

    public enum Boarding { UNTRACKED, STARTED, CONTINUED, SAME_TRAIN }

    /** Settled receipts kept for {@code /atcs fare receipts}; outstanding ones are always kept. */
    static final int KEPT_RECEIPTS = 1000;
    /** A journey with more rides than this is charged and a new one begun. */
    static final int MAX_LEGS = 64;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BillingLedger ledger;
    private final BillingLedger.State state;
    private final Supplier<Network> networks;
    private final Supplier<FareTable> tariffs;
    private final RouteMeasure measure;
    private final FarePayment payment;
    private final Consumer<Charge> receipts;
    private boolean failed;
    private boolean dirty;

    /**
     * @param tariffs  the fares to begin new journeys with; journeys keep the fares they began with
     * @param receipts told of every finished journey: paid, free, declined (still DUE) or UNCERTAIN
     */
    public RideBilling(BillingLedger ledger, Supplier<Network> networks, Supplier<FareTable> tariffs,
                       RouteMeasure measure, FarePayment payment, Consumer<Charge> receipts, long now) throws IOException {
        this.ledger = ledger;
        this.networks = networks;
        this.tariffs = tariffs;
        this.measure = measure;
        this.payment = payment;
        this.receipts = receipts;
        state = ledger.load();
        boolean interrupted = false;
        for (Map.Entry<String, Charge> entry : state.charges().entrySet()) {
            // Vault has no idempotency key: after a crash we cannot know whether an in-flight debit ran.
            if (entry.getValue().status() == Status.PROCESSING) {
                entry.setValue(entry.getValue().withStatus(Status.UNCERTAIN, entry.getValue().at()));
                interrupted = true;
            }
        }
        // Nobody is still on a train the server was stopped under; give them the window to board again.
        state.rides().replaceAll((player, ride) -> ride.train() == null ? ride : left(ride, now));
        pruneTariffs();
        if (interrupted) {
            save(true);
        } else if (!state.rides().isEmpty()) {
            save(false);
        }
    }

    public boolean healthy() {
        return !failed;
    }

    /** Whether journeys are being tracked and charged right now. */
    public boolean active() {
        return !failed && tariffs.get().billing().enabled() && payment.available();
    }

    public List<Ride> rides() {
        return List.copyOf(state.rides().values());
    }

    public Optional<Ride> ride(UUID player) {
        return Optional.ofNullable(state.rides().get(player));
    }

    public List<Charge> charges() {
        return List.copyOf(state.charges().values());
    }

    public Optional<Charge> charge(String id) {
        return Optional.ofNullable(state.charges().get(id));
    }

    public FareTable tariff(Ride ride) {
        return state.tariffs().get(ride.tariff());
    }

    /**
     * Whether a player may board a paid train: what they owe is collected first, then they need the
     * journey minimum. Free lines, and every boarding while billing is inactive, are always allowed.
     */
    public @Nullable Refusal admission(UUID player, String line, long now) throws IOException {
        if (!active()) {
            return null;
        }
        FareTable table = tariffs.get();
        if (table.billing().freeLines().contains(line)) {
            return null;
        }
        retry(player, now);
        double due = 0;
        String first = null;
        for (Charge charge : state.charges().values()) {
            if (!charge.player().equals(player) || !charge.outstanding()) {
                continue;
            }
            if (charge.status() != Status.DUE) {
                return new Refusal(Reason.REVIEW, charge.amount(), charge.currency(), charge.id());
            }
            due += charge.amount();
            first = first == null ? charge.id() : first;
        }
        if (first != null) {
            return new Refusal(Reason.DEBT, due, table.currency(), first);
        }
        double minimum = Math.max(table.billing().minimum(), table.formula().minimum());
        if (minimum > 0 && !payment.has(player, minimum)) {
            return new Refusal(Reason.BALANCE, minimum, table.currency(), null);
        }
        return null;
    }

    /**
     * A player sat down on a train running {@code service}.
     *
     * @param index   the stop of {@code service} the train last reached, or -1 before the first one
     * @param station where the ride begins: that stop's station, or before the first one the next stop's
     */
    public Boarding board(UUID player, String name, String train, Service service, int index, String station,
                          long now) throws IOException {
        if (!active()) {
            return Boarding.UNTRACKED;
        }
        Ride previous = state.rides().get(player);
        if (previous != null && train.equals(previous.train())) {
            return Boarding.SAME_TRAIN;
        }
        if (previous != null && previous.train() != null) {
            // The exit from the last train was missed; treat it as having happened now.
            previous = left(previous, now);
            state.rides().put(player, previous);
        }
        boolean continued = previous != null && previous.legs().size() < MAX_LEGS && connects(previous, station, now);
        if (previous != null && !continued) {
            finish(player, now);
        }
        String tariff = continued ? previous.tariff() : intern(tariffs.get());
        List<FareCalculator.RideLeg> legs = new ArrayList<>(continued ? previous.legs() : List.of());
        legs.add(new FareCalculator.RideLeg(service.key().lineId(), station, station, 0, 0));
        state.rides().put(player, new Ride(continued ? previous.id() : newId(), player, name,
                train, service.key().toString(), index, now, continued ? previous.startedAt() : now, 0, tariff, legs));
        dirty = true;
        return continued ? Boarding.CONTINUED : Boarding.STARTED;
    }

    /**
     * Whether boarding at {@code station} now continues the journey: it must be boarded before the
     * window closes, where the last ride ended or across a transfer from there, and not back at the
     * station the journey began from, which starts a new one.
     */
    boolean connects(Ride ride, String station, long now) {
        if (ride.train() != null || now >= ride.expiresAt()) {
            return false;
        }
        if (station.equals(ride.origin()) && ride.legs().stream().anyMatch(FareCalculator.RideLeg::moved)) {
            return false;
        }
        return ride.destination().equals(station) || networks.get().transfer(ride.destination(), station).isPresent();
    }

    /**
     * A train reached stop {@code index} of {@code service}, calling or passing. Every rider on it is
     * carried there along the service, counting the calling stops and track distance on the way; an
     * event missed in between still counts, but never more than one lap of a circular service.
     */
    public void visit(String train, Service service, int index, long at) {
        if (!active() || index < 0 || index >= service.size()) {
            return;
        }
        String station = service.stop(index).stationId();
        for (Ride ride : rides()) {
            if (!train.equals(ride.train()) || !service.key().toString().equals(ride.service())
                    || at < ride.visitAt() || index == ride.lastIndex()) {
                continue;
            }
            List<FareCalculator.RideLeg> legs = new ArrayList<>(ride.legs());
            FareCalculator.RideLeg last = legs.removeLast();
            int stops = 0;
            double blocks = 0;
            boolean reached = false;
            int cursor = ride.lastIndex();
            for (int step = 0; step < service.size() && cursor >= 0 && cursor < service.size(); step++) {
                double distance = measure.segmentBlocks(service, cursor).orElse(0);
                if (Double.isFinite(distance) && distance > 0) {
                    blocks += distance;
                }
                cursor = service.nextIndex(cursor);
                if (cursor < 0) {
                    break;
                }
                if (service.stop(cursor).stops()) {
                    stops++;
                }
                if (cursor == index) {
                    reached = true;
                    break;
                }
            }
            if (!reached) {
                // Boarded before the first stop, or the train left its route: count this stop alone,
                // never a distance that was not seen.
                stops = station.equals(last.to()) || !service.stop(index).stops() ? 0 : 1;
                blocks = 0;
            }
            legs.add(new FareCalculator.RideLeg(last.line(), last.from(), station,
                    Math.addExact(last.stops(), stops), last.blocks() + blocks));
            state.rides().put(ride.player(), new Ride(ride.id(), ride.player(), ride.name(), ride.train(),
                    ride.service(), index, at, ride.startedAt(), 0, ride.tariff(), legs));
            dirty = true;
        }
    }

    /** A train changed service with its riders aboard, for example continuing as the return service. */
    public void serviceChanged(String train, Service service, long at) {
        if (!active()) {
            return;
        }
        for (Ride ride : rides()) {
            if (!train.equals(ride.train()) || ride.service().equals(service.key().toString())) {
                continue;
            }
            String station = ride.destination();
            int index = -1;
            for (int i = 0; i < service.size(); i++) {
                if (service.stop(i).stationId().equals(station)) {
                    index = i;
                    break;
                }
            }
            List<FareCalculator.RideLeg> legs = new ArrayList<>(ride.legs());
            legs.add(new FareCalculator.RideLeg(service.key().lineId(), station, station, 0, 0));
            state.rides().put(ride.player(), new Ride(ride.id(), ride.player(), ride.name(), train,
                    service.key().toString(), index, at, ride.startedAt(), 0, ride.tariff(), legs));
            dirty = true;
        }
    }

    /** A player got off; the journey is charged once the transfer window closes. Returns the journey. */
    public Optional<Ride> leave(UUID player, long now) {
        Ride ride = state.rides().get(player);
        if (ride == null || ride.train() == null) {
            return Optional.empty();
        }
        Ride off = left(ride, now);
        state.rides().put(player, off);
        dirty = true;
        return Optional.of(off);
    }

    /** What the journey would cost if it ended now. */
    public FareCalculator.Quote fareSoFar(Ride ride) {
        return calculator(tariff(ride)).quoteRide(ride.legs());
    }

    /**
     * Ends rides of players no longer on their train, charges journeys whose window has closed and
     * writes changes out. While billing is inactive, journeys in progress are dropped uncharged.
     *
     * @param riding whether the player of a ride is still online and on the ride's train
     */
    public void tick(long now, Predicate<Ride> riding) throws IOException {
        if (failed) {
            return;
        }
        if (!active()) {
            if (!state.rides().isEmpty()) {
                state.rides().clear();
                pruneTariffs();
                dirty = true;
            }
        } else {
            for (Ride ride : rides()) {
                if (ride.train() != null && !riding.test(ride)) {
                    state.rides().put(ride.player(), left(ride, now));
                    dirty = true;
                } else if (ride.train() == null && now >= ride.expiresAt()) {
                    finish(ride.player(), now);
                }
            }
        }
        flush();
    }

    public void flush() throws IOException {
        if (dirty && !failed) {
            save(false);
        }
    }

    /** Charges a journey now. Its bill is on disk before the debit, so it can never be charged twice. */
    void finish(UUID player, long now) throws IOException {
        Ride ride = state.rides().remove(player);
        if (ride == null) {
            return;
        }
        FareTable tariff = tariff(ride);
        pruneTariffs();
        dirty = true;
        double quoted = calculator(tariff).quoteRide(ride.legs()).fare();
        double amount = BigDecimal.valueOf(quoted).setScale(2, RoundingMode.HALF_UP).doubleValue();
        Charge charge = new Charge(ride.id(), player, ride.name(), ride.origin(), ride.destination(), tariff.currency(),
                amount, amount > 0 ? Status.DUE : Status.PAID, now);
        if (amount <= 0 || state.charges().containsKey(charge.id())) {
            if (amount <= 0) {
                receipts.accept(charge);
            }
            return;
        }
        state.charges().put(charge.id(), charge);
        pruneReceipts();
        save(true);
        receipts.accept(collect(charge.id(), now));
    }

    /** Collects what a player owes when the economy says they can pay; receipts only for what was paid. */
    public void retry(UUID player, long now) throws IOException {
        if (!active()) {
            return;
        }
        for (Charge charge : charges()) {
            if (charge.player().equals(player) && charge.status() == Status.DUE) {
                Charge result = collect(charge.id(), now);
                if (result.status() == Status.PAID) {
                    receipts.accept(result);
                }
            }
        }
    }

    private Charge collect(String id, long now) throws IOException {
        Charge charge = state.charges().get(id);
        if (charge.status() != Status.DUE || !payment.available() || !payment.has(charge.player(), charge.amount())) {
            return charge;
        }
        state.charges().put(id, charge.withStatus(Status.PROCESSING, now));
        save(true);
        FarePayment.Result result;
        try {
            result = payment.withdraw(charge.player(), charge.amount());
        } catch (RuntimeException exception) {
            result = FarePayment.Result.UNCERTAIN;
        }
        Charge updated = charge.withStatus(switch (result) {
            case PAID -> Status.PAID;
            case DECLINED -> Status.DUE;
            case UNCERTAIN -> Status.UNCERTAIN;
        }, now);
        state.charges().put(id, updated);
        save(true);
        return updated;
    }

    /**
     * Settles an outstanding receipt by hand: {@code PAID} when the money did leave the account,
     * {@code WAIVED} to forgive it, {@code DUE} to collect it again at the next boarding, only once
     * the account shows it was not taken. Never moves money itself.
     */
    public Charge resolve(String id, Status status, long now) throws IOException {
        Charge charge = state.charges().get(id);
        if (charge == null || !charge.outstanding()) {
            throw new IllegalArgumentException("No outstanding receipt " + id);
        }
        if (status != Status.PAID && status != Status.WAIVED && status != Status.DUE) {
            throw new IllegalArgumentException("A receipt can be marked paid, waived or due again");
        }
        requireHealthy();
        Charge updated = charge.withStatus(status, now);
        state.charges().put(id, updated);
        save(true);
        return updated;
    }

    private Ride left(Ride ride, long now) {
        long window = tariff(ride).billing().transferWindowSeconds() * 1000L;
        return new Ride(ride.id(), ride.player(), ride.name(), null, ride.service(), ride.lastIndex(), ride.visitAt(),
                ride.startedAt(), now + window, ride.tariff(), ride.legs());
    }

    private FareCalculator calculator(FareTable tariff) {
        Network network = networks.get();
        return new FareCalculator(tariff, network, RoutePlanner.of(network, new TravelTimes(0.3), measure));
    }

    /** A receipt number short enough to read out: eight hex digits, unique among those kept. */
    private String newId() {
        while (true) {
            String id = String.format("%08x", RANDOM.nextInt());
            if (!state.charges().containsKey(id) && state.rides().values().stream().noneMatch(ride -> ride.id().equals(id))) {
                return id;
            }
        }
    }

    /** Journeys share a stored copy of the fares they began under instead of each carrying one. */
    private String intern(FareTable table) {
        for (Map.Entry<String, FareTable> entry : state.tariffs().entrySet()) {
            if (entry.getValue().equals(table)) {
                return entry.getKey();
            }
        }
        String id = UUID.randomUUID().toString();
        state.tariffs().put(id, table);
        return id;
    }

    private void pruneTariffs() {
        Set<String> used = new HashSet<>();
        state.rides().values().forEach(ride -> used.add(ride.tariff()));
        state.tariffs().keySet().retainAll(used);
    }

    private void pruneReceipts() {
        long settled = state.charges().values().stream().filter(charge -> !charge.outstanding()).count();
        Iterator<Charge> iterator = state.charges().values().iterator();
        while (settled > KEPT_RECEIPTS && iterator.hasNext()) {
            if (!iterator.next().outstanding()) {
                iterator.remove();
                settled--;
            }
        }
    }

    private void requireHealthy() throws IOException {
        if (failed) {
            throw new IOException("The fare ledger could not be written; automatic fares are stopped until a restart");
        }
    }

    private void save(boolean durable) throws IOException {
        requireHealthy();
        try {
            ledger.save(state, durable);
            dirty = false;
        } catch (IOException exception) {
            failed = true;
            throw exception;
        }
    }
}
