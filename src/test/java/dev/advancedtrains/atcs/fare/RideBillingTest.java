package dev.advancedtrains.atcs.fare;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.operation.RouteMeasure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;

import static dev.advancedtrains.atcs.TestNetworks.network;
import static dev.advancedtrains.atcs.TestNetworks.service;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RideBillingTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final long WINDOW = 180_000;

    private final Service east = service("a/east", "s1", "s2", "s3", "s4");
    private final Service west = service("a/west", "s4", "s3", "s2", "s1");
    private final Service out = service("b/out", "s4", "s5");
    private final Service nerdy = service("nerdy/loop", "s2", "s3", "s4");
    /** Line a: s1 to s4 both ways; line b: s4 to s5; the free line nerdy: s2 to s4. Every segment 300 blocks. */
    private final Network rails = network(5, east, west, out, nerdy);
    private final RouteMeasure measure = (service, index) -> OptionalDouble.of(300);

    @TempDir
    Path folder;

    private final Wallet wallet = new Wallet();
    private final List<BillingLedger.Charge> receipts = new ArrayList<>();
    /** Two per journey, one per calling stop travelled, nerdy free; 3 at least once you board a paid train. */
    private FareTable table = FareTable.EMPTY.withFormula(new FareTable.Formula(2, 1, 0, List.of(), 0, 0))
            .withBilling(new FareTable.Billing(true, Set.of("nerdy"), 3, 180, 0, 50));

    /** An economy that records every debit and can be told how the next one ends. */
    private static final class Wallet implements FarePayment {
        final Map<UUID, Double> balances = new HashMap<>();
        final List<Double> withdrawals = new ArrayList<>();
        boolean available = true;
        Result next = Result.PAID;

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public boolean has(UUID player, double amount) {
            return balances.getOrDefault(player, 0.0) >= amount;
        }

        @Override
        public Result withdraw(UUID player, double amount) {
            withdrawals.add(amount);
            if (next == Result.PAID) {
                balances.merge(player, -amount, Double::sum);
            }
            return next;
        }
    }

    private BillingLedger ledger() {
        return new BillingLedger(folder.resolve("fare-ledger.json"));
    }

    private RideBilling billing(long now) throws IOException {
        return new RideBilling(ledger(), () -> rails, () -> table, measure, wallet, receipts::add, now);
    }

    /** Boards {@code train} at stop {@code index}, then rides it on to each later stop given. */
    private static void ride(RideBilling billing, String train, Service service, int index, long at, int... visits)
            throws IOException {
        billing.board(ALICE, "Alice", train, service, index, service.stop(index).stationId(), at);
        for (int visit : visits) {
            billing.visit(train, service, visit, at += 1000);
        }
    }

    private static final java.util.function.Predicate<BillingLedger.Ride> OFF = ride -> false;

    @Test
    void chargesAJourneyOnceItsTransferWindowCloses() throws IOException {
        wallet.balances.put(ALICE, 10.0);
        RideBilling billing = billing(0);
        ride(billing, "t1", east, 0, 0, 1, 2);
        billing.leave(ALICE, 10_000);
        billing.tick(10_000 + WINDOW - 1, OFF);
        assertTrue(wallet.withdrawals.isEmpty(), "nothing is charged while the passenger may still change trains");
        billing.tick(10_000 + WINDOW, OFF);
        assertEquals(List.of(4.0), wallet.withdrawals, "flat 2 and two stops");
        assertEquals(6.0, wallet.balances.get(ALICE));
        assertEquals(1, receipts.size());
        BillingLedger.Charge charge = receipts.getFirst();
        assertEquals(BillingLedger.Status.PAID, charge.status());
        assertEquals("s1", charge.from());
        assertEquals("s3", charge.to());
        billing.tick(10_000 + 2 * WINDOW, OFF);
        billing(20 * WINDOW).tick(20 * WINDOW, OFF);
        assertEquals(1, wallet.withdrawals.size(), "a paid journey is never charged again, not even after a restart");
    }

    @Test
    void endingWhereYouBoardedStillCostsTheMinimum() throws IOException {
        wallet.balances.put(ALICE, 10.0);
        RideBilling billing = billing(0);
        ride(billing, "t1", east, 1, 0);
        billing.leave(ALICE, 5_000);
        billing.tick(5_000 + WINDOW, OFF);
        assertEquals(List.of(3.0), wallet.withdrawals);
    }

    @Test
    void freeLinesCostNothingAndAreNeverRefused() throws IOException {
        RideBilling billing = billing(0);
        assertNull(billing.admission(ALICE, "nerdy", 0), "no balance is needed for a free line");
        assertEquals(RideBilling.Reason.BALANCE, billing.admission(ALICE, "a", 0).reason());
        ride(billing, "n1", nerdy, 0, 0, 1, 2);
        billing.leave(ALICE, 5_000);
        billing.tick(5_000 + WINDOW, OFF);
        assertTrue(wallet.withdrawals.isEmpty());
        assertEquals(1, receipts.size());
        assertEquals(0, receipts.getFirst().amount());
        assertTrue(billing.charges().isEmpty(), "free journeys leave nothing to settle");
    }

    @Test
    void aFreeRideBetweenTwoPaidLinesKeepsTheTransferDiscount() throws IOException {
        wallet.balances.put(ALICE, 10.0);
        RideBilling billing = billing(0);
        ride(billing, "t1", east, 0, 0, 1);
        billing.leave(ALICE, 5_000);
        ride(billing, "n1", nerdy, 0, 10_000, 1, 2);
        billing.leave(ALICE, 20_000);
        assertEquals(RideBilling.Boarding.CONTINUED,
                billing.board(ALICE, "Alice", "b1", out, 0, "s4", 30_000));
        billing.visit("b1", out, 1, 40_000);
        billing.leave(ALICE, 50_000);
        billing.tick(50_000 + WINDOW, OFF);
        // line a s1 to s2: 2 + 1 = 3; nerdy free; line b s4 to s5 (3) at half price.
        assertEquals(List.of(4.5), wallet.withdrawals);
        assertEquals("s5", receipts.getFirst().to());
    }

    @Test
    void reboardingTheSameLineIsOneRide() throws IOException {
        wallet.balances.put(ALICE, 10.0);
        RideBilling billing = billing(0);
        ride(billing, "t1", east, 0, 0, 1);
        billing.leave(ALICE, 5_000);
        ride(billing, "t2", east, 1, 10_000, 2, 3);
        billing.leave(ALICE, 20_000);
        billing.tick(20_000 + WINDOW, OFF);
        assertEquals(List.of(5.0), wallet.withdrawals, "s1 to s4 is 2 + 3, not two journeys of 3 and 4");
    }

    @Test
    void boardingAfterTheWindowStartsANewJourney() throws IOException {
        wallet.balances.put(ALICE, 10.0);
        RideBilling billing = billing(0);
        ride(billing, "t1", east, 0, 0, 1);
        billing.leave(ALICE, 5_000);
        assertEquals(RideBilling.Boarding.STARTED, billing.board(ALICE, "Alice", "t2", east, 1, "s2", 5_000 + WINDOW));
        assertEquals(List.of(3.0), wallet.withdrawals, "the first journey is charged as the second begins");
    }

    @Test
    void comingBackToTheOriginStartsANewJourney() throws IOException {
        wallet.balances.put(ALICE, 20.0);
        RideBilling billing = billing(0);
        ride(billing, "t1", east, 0, 0, 1);
        billing.leave(ALICE, 5_000);
        ride(billing, "t2", west, 2, 10_000, 3);
        billing.leave(ALICE, 15_000);
        assertEquals(RideBilling.Boarding.STARTED, billing.board(ALICE, "Alice", "t3", east, 0, "s1", 20_000),
                "a return trip is not one long journey");
        assertEquals(1, wallet.withdrawals.size());
        assertFalse(billing.connects(billing.ride(ALICE).orElseThrow(), "s1", 21_000), "still on a train");
    }

    @Test
    void aJourneyCannotContinueFromAnUnconnectedStation() throws IOException {
        wallet.balances.put(ALICE, 20.0);
        RideBilling billing = billing(0);
        ride(billing, "t1", east, 0, 0, 1);
        billing.leave(ALICE, 5_000);
        assertEquals(RideBilling.Boarding.STARTED, billing.board(ALICE, "Alice", "b1", out, 0, "s4", 10_000));
    }

    @Test
    void stopsMissedInBetweenStillCountButNotBeforeTheFirst() throws IOException {
        wallet.balances.put(ALICE, 20.0);
        RideBilling billing = billing(0);
        billing.board(ALICE, "Alice", "t1", east, -1, "s1", 0);
        billing.visit("t1", east, 0, 1_000);
        billing.visit("t1", east, 3, 2_000);
        BillingLedger.Ride ride = billing.ride(ALICE).orElseThrow();
        assertEquals(new FareCalculator.RideLeg("a", "s1", "s4", 3, 900), ride.legs().getFirst());
        billing.visit("t1", east, 2, 1_500);
        assertEquals(ride, billing.ride(ALICE).orElseThrow(), "an event older than the last is ignored");
    }

    @Test
    void aDeclinedFareIsCollectedAtTheNextBoarding() throws IOException {
        wallet.balances.put(ALICE, 3.5);
        RideBilling billing = billing(0);
        ride(billing, "t1", east, 0, 0, 1, 2);
        billing.leave(ALICE, 5_000);
        billing.tick(5_000 + WINDOW, OFF);
        assertTrue(wallet.withdrawals.isEmpty(), "an unaffordable fare is not attempted");
        assertEquals(BillingLedger.Status.DUE, receipts.getFirst().status());
        RideBilling.Refusal refusal = billing.admission(ALICE, "a", WINDOW * 2);
        assertEquals(RideBilling.Reason.DEBT, refusal.reason());
        assertEquals(4, refusal.amount());
        assertNull(billing.admission(ALICE, "nerdy", WINDOW * 2), "free lines stay open to those who owe");
        wallet.balances.put(ALICE, 10.0);
        assertNull(billing.admission(ALICE, "a", WINDOW * 3));
        assertEquals(List.of(4.0), wallet.withdrawals);
        assertEquals(BillingLedger.Status.PAID, receipts.getLast().status());

        wallet.next = FarePayment.Result.DECLINED;
        ride(billing, "t2", east, 0, WINDOW * 4, 1);
        billing.leave(ALICE, WINDOW * 4 + 5_000);
        billing.tick(WINDOW * 5 + 5_000, OFF);
        assertEquals(BillingLedger.Status.DUE, receipts.getLast().status(), "a declined debit stays due");
    }

    @Test
    void anUnconfirmedDebitIsNeverRetriedAutomatically() throws IOException {
        wallet.balances.put(ALICE, 10.0);
        wallet.next = FarePayment.Result.UNCERTAIN;
        RideBilling billing = billing(0);
        ride(billing, "t1", east, 0, 0, 1);
        billing.leave(ALICE, 5_000);
        billing.tick(5_000 + WINDOW, OFF);
        String receipt = receipts.getLast().id();
        assertEquals(BillingLedger.Status.UNCERTAIN, receipts.getLast().status());
        wallet.next = FarePayment.Result.PAID;
        RideBilling.Refusal refusal = billing.admission(ALICE, "a", WINDOW * 2);
        assertEquals(RideBilling.Reason.REVIEW, refusal.reason());
        assertEquals(receipt, refusal.receipt());
        assertEquals(1, wallet.withdrawals.size());
        assertThrows(IllegalArgumentException.class, () -> billing.resolve(receipt, BillingLedger.Status.UNCERTAIN, 0));
        billing.resolve(receipt, BillingLedger.Status.DUE, WINDOW * 3);
        assertNull(billing.admission(ALICE, "a", WINDOW * 3), "staff found it was not taken; it is collected now");
        assertEquals(2, wallet.withdrawals.size());
        assertThrows(IllegalArgumentException.class, () -> billing.resolve(receipt, BillingLedger.Status.WAIVED, 0),
                "a settled receipt cannot be settled again");
    }

    @Test
    void aDebitInterruptedByACrashIsLeftForStaff() throws IOException {
        BillingLedger.Charge processing = new BillingLedger.Charge("0000beef", ALICE, "Alice", "s1", "s2", "$", 3,
                BillingLedger.Status.PROCESSING, 0);
        ledger().save(new BillingLedger.State(BillingLedger.FORMAT_VERSION, Map.of(), Map.of(),
                Map.of(processing.id(), processing)), true);
        wallet.balances.put(ALICE, 10.0);
        RideBilling billing = billing(1_000);
        assertEquals(BillingLedger.Status.UNCERTAIN, billing.charge("0000beef").orElseThrow().status());
        assertEquals(RideBilling.Reason.REVIEW, billing.admission(ALICE, "a", 2_000).reason());
        assertTrue(wallet.withdrawals.isEmpty());
        assertEquals(BillingLedger.Status.UNCERTAIN, ledger().load().charges().get("0000beef").status(), "written back at once");
        billing.resolve("0000beef", BillingLedger.Status.PAID, 3_000);
        assertNull(billing.admission(ALICE, "a", 4_000));
        assertTrue(wallet.withdrawals.isEmpty(), "resolving never moves money");
    }

    @Test
    void nothingIsTrackedOrChargedWhileBillingIsInactive() throws IOException {
        wallet.balances.put(ALICE, 10.0);
        table = table.withBilling(new FareTable.Billing(false, Set.of(), 3, 180, 0, 0));
        RideBilling billing = billing(0);
        assertNull(billing.admission(ALICE, "a", 0));
        assertEquals(RideBilling.Boarding.UNTRACKED, billing.board(ALICE, "Alice", "t1", east, 0, "s1", 0));

        table = table.withBilling(new FareTable.Billing(true, Set.of(), 3, 180, 0, 0));
        ride(billing, "t1", east, 0, 0, 1);
        wallet.available = false;
        assertNull(billing.admission(ALICE, "a", 2_000), "no economy: nobody is refused");
        billing.tick(3_000, ride -> true);
        assertTrue(billing.rides().isEmpty(), "journeys in progress end without a charge");
        wallet.available = true;
        billing.tick(3_000 + WINDOW, OFF);
        assertTrue(wallet.withdrawals.isEmpty());
        assertTrue(receipts.isEmpty());
    }

    @Test
    void journeysKeepTheFaresTheyBeganWith() throws IOException {
        wallet.balances.put(ALICE, 10.0);
        RideBilling billing = billing(0);
        ride(billing, "t1", east, 0, 0, 1);
        table = table.withFormula(new FareTable.Formula(5, 1, 0, List.of(), 0, 0));
        billing.leave(ALICE, 5_000);
        billing.tick(5_000 + WINDOW, OFF);
        assertEquals(List.of(3.0), wallet.withdrawals);
    }

    @Test
    void journeysSurviveARestartAndRidersGetTheWindowToBoardAgain() throws IOException {
        wallet.balances.put(ALICE, 10.0);
        RideBilling before = billing(0);
        ride(before, "t1", east, 0, 0, 1, 2);
        before.flush();
        String json = Files.readString(folder.resolve("fare-ledger.json"), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"s3\""), json);

        RideBilling after = billing(60_000);
        BillingLedger.Ride ride = after.ride(ALICE).orElseThrow();
        assertNull(ride.train(), "nobody is on a train the server stopped under");
        assertEquals(60_000 + WINDOW, ride.expiresAt());
        assertEquals(List.of(new FareCalculator.RideLeg("a", "s1", "s3", 2, 600)), ride.legs());
        assertEquals(table, after.tariff(ride));
        assertEquals(RideBilling.Boarding.CONTINUED, after.board(ALICE, "Alice", "t9", east, 2, "s3", 70_000));
    }

    @Test
    void anUnreadableLedgerStopsBillingAndIsLeftAlone() throws IOException {
        Path file = folder.resolve("fare-ledger.json");
        Files.writeString(file, "{\"version\": 1, \"charges\": {\"x\": {\"amount\": \"lots\"}}", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> billing(0));
        assertEquals("{\"version\": 1, \"charges\": {\"x\": {\"amount\": \"lots\"}}",
                Files.readString(file, StandardCharsets.UTF_8));
    }
}
