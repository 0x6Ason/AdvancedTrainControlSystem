package dev.advancedtrains.atcs.fare;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Transfer;
import dev.advancedtrains.atcs.operation.RouteMeasure;
import dev.advancedtrains.atcs.operation.RoutePlanner;
import dev.advancedtrains.atcs.operation.TravelTimes;
import org.bukkit.configuration.InvalidConfigurationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;

import static dev.advancedtrains.atcs.TestNetworks.network;
import static dev.advancedtrains.atcs.TestNetworks.service;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FareTest {
    /** Line a: s1 s2 s3 s4 both ways; line b: s4 s5. Every segment 300 blocks, a minute (nothing learned). */
    private final Network rails = network(5,
            service("a/east", "s1", "s2", "s3", "s4"), service("a/west", "s4", "s3", "s2", "s1"),
            service("b/out", "s4", "s5"), service("b/back", "s5", "s4"));
    /** The same with a one-minute walk between s3 and s5. */
    private final Network withWalk = rails.toBuilder().transfer(new Transfer("s3", "s5", 60, true)).build();
    private final RouteMeasure measure = (service, index) -> OptionalDouble.of(300);
    private final TravelTimes times = new TravelTimes(0.3);

    private FareCalculator calculator(Network network, FareTable table) {
        return new FareCalculator(table, network, RoutePlanner.of(network, times, measure));
    }

    private static FareTable.Formula formula() {
        return new FareTable.Formula(2, 0.5, 1, List.of(new FareTable.Band(500, 0), new FareTable.Band(1000, 2), new FareTable.Band(0, 4)), 0.5, 0);
    }

    @Test
    void plansTheQuickestRouteWithChangesAndWalks() {
        RoutePlanner planner = RoutePlanner.of(rails, times, measure);
        RoutePlanner.Route direct = planner.route("s1", "s4").orElseThrow();
        assertEquals(1, direct.legs().size());
        assertEquals(3, direct.stops());
        assertEquals(0, direct.changes());
        assertEquals(900, direct.blocks(), 0.01);
        assertTrue(direct.distanceKnown());
        RoutePlanner.Route changed = planner.route("s1", "s5").orElseThrow();
        assertEquals(2, changed.legs().size(), "ride line a to s4, change to line b: " + changed.legs());
        assertEquals(1, changed.changes());
        assertEquals(4, changed.stops());
        assertEquals(Optional.empty(), planner.route("s1", "nowhere"));
        assertEquals(0, planner.route("s2", "s2").orElseThrow().seconds());

        RoutePlanner walking = RoutePlanner.of(withWalk, times, measure);
        RoutePlanner.Route walked = walking.route("s3", "s5").orElseThrow();
        assertEquals(1, walked.legs().size(), "the 60 s walk beats riding to s4 and changing: " + walked.legs());
        assertTrue(walked.legs().get(0).walk());
        RoutePlanner.Route rideThenWalk = walking.route("s1", "s5").orElseThrow();
        assertEquals(2, rideThenWalk.legs().size(), rideThenWalk.legs().toString());
        assertEquals(0, rideThenWalk.changes(), "a walk is not a change of train");
        assertEquals(2, rideThenWalk.stops());
    }

    @Test
    void layersWinInOrder() {
        FareTable table = FareTable.EMPTY.withFormula(formula())
                .withZone("s1", "A").withZone("s2", "A").withZone("s3", "B").withZoneFare("a", "b", 7.0)
                .withPair("s1", "s4", new FareTable.Pair(12, true));
        FareCalculator calculator = calculator(rails, table);
        FareCalculator.Quote pair = calculator.quote("s4", "s1").orElseThrow();
        assertEquals(12, pair.fare());
        assertEquals(FareCalculator.Layer.PAIR, pair.layer());
        FareCalculator.Quote zone = calculator.quote("s1", "s3").orElseThrow();
        assertEquals(7, zone.fare());
        assertEquals(FareCalculator.Layer.ZONE, zone.layer());
        FareCalculator.Quote formula = calculator.quote("s1", "s5").orElseThrow();
        assertEquals(FareCalculator.Layer.FORMULA, formula.layer());
        // flat 2 + 4 stops * 0.5 + 1 change * 1 + band for 1200 blocks (4) = 9
        assertEquals(9, formula.fare(), 0.001, String.join("; ", formula.reasoning()));
        assertEquals(0, calculator.quote("s2", "s2").orElseThrow().fare());
        assertTrue(calculator.quote("s1", "nowhere").isEmpty());
        assertFalse(FareTable.EMPTY.configured());
        assertTrue(table.configured());
    }

    @Test
    void roundsAndFloors() {
        FareTable.Formula formula = new FareTable.Formula(1.2, 0, 0, List.of(), 0.5, 3);
        assertEquals(3, formula.finish(1.2), "under the minimum");
        FareTable.Formula fine = new FareTable.Formula(0, 0.7, 0, List.of(), 0.5, 0);
        assertEquals(2.0, fine.finish(0.7 * 3), 0.001, "2.1 rounded to the nearest 0.5");
    }

    @Test
    void codecRoundTripsAndReportsErrors() {
        FareTable table = FareTable.EMPTY.withCurrency("€").withFormula(formula())
                .withZone("s1", "A").withZoneFare("a", "b", 7.0)
                .withPair("s1", "s4", new FareTable.Pair(12, true)).withPair("s2", "s3", new FareTable.Pair(4, false));
        FareCodec codec = new FareCodec();
        String yaml = codec.write(table);
        FareCodec.ReadResult read = codec.read(yaml);
        assertTrue(read.clean(), read.errors()::toString);
        assertEquals(table, read.table());
        assertTrue(yaml.contains("keep: true"), yaml);
        FareCodec.ReadResult bad = codec.read("formula:\n  flat: many\npairs:\n  s1: 3\nzone-fares:\n  x: 1\n");
        assertEquals(3, bad.errors().size(), bad.errors()::toString);
    }

    @Test
    void draftKeepsHandEditsOnApply() throws InvalidConfigurationException {
        FareTable table = FareTable.EMPTY.withFormula(formula()).withPair("s1", "s2", new FareTable.Pair(99, true));
        FareMatrix.Draft draft = FareMatrix.generate(rails, calculator(rails, table));
        assertEquals(10, draft.pairs().size(), "every pair of five stations");
        assertEquals(99, draft.pairs().get(FareTable.pairKey("s1", "s2")), "a pair set by hand is not regenerated");
        Map<String, Double> corrected = new TreeMap<>(draft.pairs());
        corrected.put("s1|s3", 1.0);
        String yaml = FareMatrix.writeDraft(new FareMatrix.Draft(corrected, draft.generated()), rails, table, Instant.EPOCH);
        FareMatrix.Draft edited = FareMatrix.readDraft(yaml);
        assertEquals(10, edited.pairs().size());
        assertEquals(Map.of("s1|s3", 1.0), edited.edited());
        FareTable applied = FareMatrix.apply(table, edited);
        assertEquals(10, applied.pairs().size());
        assertTrue(applied.pair("s1", "s3").orElseThrow().keep(), "the edited pair is kept");
        assertTrue(applied.pair("s1", "s2").orElseThrow().keep(), "the pair set by hand before stays kept");
        assertFalse(applied.pair("s2", "s4").orElseThrow().keep(), "an unedited pair follows the formula next time");
        assertEquals(1.0, calculator(rails, applied).quote("s3", "s1").orElseThrow().fare());
        FareMatrix.Draft again = FareMatrix.generate(rails, calculator(rails, applied));
        assertEquals(1.0, again.pairs().get("s1|s3"), "a later generate keeps the correction");
    }

    private static FareTable.Billing billing(Set<String> free, double minimum, double discount, double percent) {
        return new FareTable.Billing(true, free, minimum, 180, discount, percent);
    }

    private static FareCalculator.RideLeg ride(String line, String from, String to, int stops, double blocks) {
        return new FareCalculator.RideLeg(line, from, to, stops, blocks);
    }

    @Test
    void billingDefaultsAreBackwardCompatibleAndOptIn() {
        FareTable oldConstructor = new FareTable("$", formula(), Map.of(), Map.of(), Map.of());
        assertEquals(FareTable.Billing.DEFAULT, oldConstructor.billing());
        assertFalse(oldConstructor.billing().enabled());
        assertEquals(180, oldConstructor.billing().transferWindowSeconds());
        FareCodec.ReadResult legacy = new FareCodec().read("formula:\n  flat: 2\n");
        assertTrue(legacy.clean(), legacy.errors()::toString);
        assertEquals(FareTable.Billing.DEFAULT, legacy.table().billing());
        FareTable enableOnly = oldConstructor.withBilling(billing(Set.of(), 0, 0, 0));
        assertEquals(calculator(rails, oldConstructor).quote("s1", "s5"), calculator(rails, enableOnly).quote("s1", "s5"),
                "opting in to payment alone must not change legacy static quotes");
    }

    @Test
    void billingRoundTripsAndAllEditsPreserveIt() throws InvalidConfigurationException {
        FareTable.Billing billing = new FareTable.Billing(true, Set.of(" Nerdy ", "A"), 3.5, 3600, 2.25, 25);
        assertEquals(Set.of("nerdy", "a"), billing.freeLines());
        assertThrows(UnsupportedOperationException.class, () -> billing.freeLines().add("other"));
        FareTable original = FareTable.EMPTY.withBilling(billing).withFormula(formula())
                .withZone("s1", "x").withZoneFare("x", "y", 7.0)
                .withPair("s1", "s2", new FareTable.Pair(40, true))
                .withPair("s2", "s3", new FareTable.Pair(17, false));
        FareCodec codec = new FareCodec();
        FareCodec.ReadResult restored = codec.read(codec.write(original));
        assertTrue(restored.clean(), restored.errors()::toString);
        assertEquals(original, restored.table());
        List<FareTable> edits = List.of(original.withCurrency("credits"), original.withFormula(FareTable.Formula.NONE),
                original.withZone("s1", "y"), original.withZone("s1", null), original.withZoneFare("x", "y", 5.0),
                original.withZoneFare("x", "y", null), original.withPair("s1", "s2", new FareTable.Pair(3, false)),
                original.withPair("s1", "s2", null), original.withPairs(Map.of()));
        for (FareTable edited : edits) {
            assertEquals(billing, edited.billing());
        }
        FareMatrix.Draft draft = FareMatrix.generate(rails, calculator(rails, original));
        assertEquals(40, draft.pairs().get("s1|s2"), "kept overrides survive generation, even on free lines");
        assertEquals(0, draft.pairs().get("s2|s3"), "unkept pairs regenerate with free-line rules");
        FareTable applied = FareMatrix.apply(original, FareMatrix.readDraft(FareMatrix.writeDraft(draft, rails, original, Instant.EPOCH)));
        assertEquals(billing, applied.billing());
        assertEquals(new FareTable.Pair(40, true), applied.pair("s1", "s2").orElseThrow());
        assertFalse(applied.pair("s2", "s3").orElseThrow().keep());
        assertEquals(0, calculator(rails, applied).quoteRide(List.of(ride("a", "s1", "s2", 1, 300))).fare(),
                "a kept pair is preserved, but it never makes a free train charge");
    }

    @Test
    void rejectsNonfiniteAndNegativeAmountsInEveryRecord() {
        for (double bad : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new FareTable.Pair(bad, true));
            assertThrows(IllegalArgumentException.class, () -> new FareTable.Band(100, bad));
            assertThrows(IllegalArgumentException.class, () -> FareTable.EMPTY.withZoneFare("x", "y", bad));
            assertThrows(IllegalArgumentException.class, () -> new FareTable.Formula(bad, 0, 0, List.of(), 0, 0));
            assertThrows(IllegalArgumentException.class, () -> new FareTable.Formula(0, bad, 0, List.of(), 0, 0));
            assertThrows(IllegalArgumentException.class, () -> new FareTable.Formula(0, 0, bad, List.of(), 0, 0));
            assertThrows(IllegalArgumentException.class, () -> new FareTable.Formula(0, 0, 0, List.of(), bad, 0));
            assertThrows(IllegalArgumentException.class, () -> new FareTable.Formula(0, 0, 0, List.of(), 0, bad));
            assertThrows(IllegalArgumentException.class, () -> billing(Set.of(), bad, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> billing(Set.of(), 0, bad, 0));
            assertThrows(IllegalArgumentException.class, () -> billing(Set.of(), 0, 0, bad));
            assertThrows(IllegalArgumentException.class, () -> ride("a", "s1", "s2", 1, bad));
        }
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new FareTable.Band(bad, 1));
        }
        assertThrows(IllegalArgumentException.class, () -> billing(Set.of(), 0, 0, 100.1));
        assertThrows(IllegalArgumentException.class, () -> new FareTable.Billing(true, Set.of(), 0, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new FareTable.Billing(true, Set.of(), 0, 3601, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> billing(Set.of("not a line"), 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ride("a", "s1", "s2", -1, 0));
    }

    @Test
    void codecRejectsInvalidNumbersAndDisablesBillingOnEveryError() {
        FareCodec codec = new FareCodec();
        List<String> monetaryFields = List.of("formula:\n  flat: %s", "formula:\n  per-stop: %s",
                "formula:\n  per-change: %s", "formula:\n  round-to: %s", "formula:\n  minimum: %s",
                "formula:\n  distance-bands: [{fare: %s}]", "pairs:\n  s1|s2: %s",
                "pairs:\n  s1|s2: {fare: %s, keep: true}", "zone-fares:\n  x-y: %s");
        for (String field : monetaryFields) {
            for (String bad : List.of("-1", ".NaN", ".inf", "-.inf", "'3'", "true", "[]", "{}")) {
                String yaml = "billing:\n  enabled: true\n" + field.formatted(bad) + "\n";
                FareCodec.ReadResult result = codec.read(yaml);
                assertFalse(result.clean(), yaml);
                assertFalse(result.table().billing().enabled(), yaml);
            }
        }
        for (String field : List.of("minimum", "transfer-discount", "transfer-discount-percent")) {
            for (String bad : List.of("-1", ".NaN", ".inf", "-.inf", "'3'", "true", "[]", "{}")) {
                String yaml = "billing:\n  enabled: true\n  " + field + ": " + bad + "\n";
                FareCodec.ReadResult result = codec.read(yaml);
                assertFalse(result.clean(), yaml);
                assertFalse(result.table().billing().enabled(), yaml);
            }
        }
        for (String bad : List.of("-1", "3601", "1.5", "1.0", "'180'", ".NaN", ".inf", "true")) {
            FareCodec.ReadResult result = codec.read("billing:\n  enabled: true\n  transfer-window-seconds: " + bad + "\n");
            assertFalse(result.clean(), bad);
            assertFalse(result.table().billing().enabled(), bad);
        }
        FareCodec.ReadResult tooHigh = codec.read("billing:\n  enabled: true\n  transfer-discount-percent: 101\n");
        assertFalse(tooHigh.clean());
        assertFalse(tooHigh.table().billing().enabled());
    }

    @Test
    void codecRejectsMalformedSectionsAndStrictTypes() {
        FareCodec codec = new FareCodec();
        for (String malformed : List.of("formula: false", "zones: []", "zone-fares: 3", "pairs: []", "currency: 4",
                "zones: {s1: [a]}", "zones: {s1: {nested: a}}", "zones: {s1: ''}", "format: '1'",
                "pairs: {s1|s2: {keep: true}}", "pairs: {s1|s2: {fare: 2, keep: 'true'}}",
                "formula: {distance-bands: {fare: 2}}", "formula: {distance-bands: [3]}",
                "formula: {distance-bands: [{fare: 2, up-to: '100'}]}",
                "formula: {distance-bands: [{fare: 2, up-to: .inf}]}",
                "formula: {distance-bands: [{fare: 2}, {fare: 3, up-to: 100}]}",
                "formula: {distance-bands: [{fare: 2, up-to: 100}, {fare: 3, up-to: 50}]}")) {
            FareCodec.ReadResult result = codec.read("billing: {enabled: true}\n" + malformed + "\n");
            assertFalse(result.clean(), malformed);
            assertFalse(result.table().billing().enabled(), malformed);
        }
        for (String malformed : List.of("billing: true", "billing: []", "billing: {enabled: 'true'}",
                "billing: {enabled: 1}", "billing: {enabled: true, free-lines: a}",
                "billing: {enabled: true, free-lines: [a, 4]}", "billing: {enabled: true, free-lines: ['bad line']}")) {
            FareCodec.ReadResult result = codec.read(malformed);
            assertFalse(result.clean(), malformed);
            assertFalse(result.table().billing().enabled(), malformed);
        }
        assertFalse(codec.read("billing: [").table().billing().enabled());
        FareCodec.ReadResult bounds = codec.read("billing: {enabled: true, transfer-window-seconds: 0, transfer-discount-percent: 100}");
        assertTrue(bounds.clean(), bounds.errors()::toString);
        assertTrue(bounds.table().billing().enabled());
    }

    @Test
    void observedRidesUseActualDistanceAndStopsRatherThanPlanner() {
        FareTable table = FareTable.EMPTY.withFormula(formula());
        FareCalculator calculator = calculator(rails, table);
        assertEquals(2.5, calculator.quote("s1", "s2").orElseThrow().fare());
        // The actual train went the long way: 2 + 6 * .5 + the 1600-block band (4).
        assertEquals(9, calculator.quoteRide(List.of(ride("a", "s1", "s2", 6, 1600))).fare());
        assertEquals(9, calculator.quoteRide(List.of(ride("removed-line", "old-start", "old-end", 6, 1600))).fare(),
                "saved rides remain priceable after a network edit removes their stations");
        FareTable layered = table.withZone("s1", "x").withZone("s2", "y").withZoneFare("x", "y", 7.0);
        assertEquals(7, calculator(rails, layered).quoteRide(List.of(ride("a", "s1", "s2", 6, 1600))).fare());
        layered = layered.withPair("s1", "s2", new FareTable.Pair(4, true));
        FareCalculator.Quote pair = calculator(rails, layered).quoteRide(List.of(ride("a", "s1", "s2", 6, 1600)));
        assertEquals(4, pair.fare());
        assertEquals(FareCalculator.Layer.PAIR, pair.layer());
    }

    @Test
    void freeLinesOverridePairsZonesAndBothMinimums() {
        FareTable table = FareTable.EMPTY.withFormula(new FareTable.Formula(10, 2, 3, List.of(), 0, 50))
                .withBilling(billing(Set.of("a"), 100, 0, 0))
                .withPair("s1", "s4", new FareTable.Pair(500, true))
                .withZone("s1", "x").withZone("s3", "y").withZoneFare("x", "y", 600.0);
        FareCalculator calculator = calculator(rails, table);
        assertEquals(0, calculator.quoteRide(List.of(ride("a", "s1", "s4", 3, 900))).fare());
        assertEquals(0, calculator.quoteRide(List.of(ride("a", "s1", "s3", 2, 600))).fare());
        assertEquals(0, calculator.quoteRide(List.of(ride("a", "s1", "s1", 4, 1200))).fare());
        assertEquals(0, calculator.quoteRide(List.of()).fare());
        assertEquals(0, calculator.quote("s1", "s4").orElseThrow().fare());
        assertEquals(0, calculator.quote("s1", "s3").orElseThrow().fare());
        assertEquals(100, calculator.quoteRide(List.of(ride("a", "s1", "s4", 3, 900), ride("b", "s4", "s5", 1, 300))).fare());
    }

    @Test
    void discountsFollowTheLastPaidLineAcrossFreeTrains() {
        FareTable table = FareTable.EMPTY.withFormula(new FareTable.Formula(10, 0, 2, List.of(), 0, 0))
                .withBilling(billing(Set.of("nerdy"), 0, 1, 50));
        FareCalculator calculator = calculator(rails, table);
        FareCalculator.RideLeg a = ride("a", "s1", "s2", 1, 300);
        FareCalculator.RideLeg free = ride("nerdy", "s2", "s3", 1, 300);
        // first paid 10; different paid line (10 + change 2) * 50% - 1 = 5
        assertEquals(15, calculator.quoteRide(List.of(a, free, ride("b", "s3", "s4", 1, 300))).fare());
        assertEquals(20, calculator.quoteRide(List.of(a, free, ride("a", "s3", "s4", 1, 300))).fare(),
                "a free train cannot turn reboarding the same paid line into a discounted transfer");
        assertEquals(10, calculator.quoteRide(List.of(free, ride("b", "s3", "s4", 1, 300))).fare(),
                "free-to-paid is a first paid boarding, not a discounted transfer");
        FareTable pairTable = table.withPair("s3", "s4", new FareTable.Pair(8, true));
        assertEquals(13, calculator(rails, pairTable).quoteRide(List.of(a, free, ride("b", "s3", "s4", 1, 300))).fare(),
                "the pair replaces formula including per-change, then receives the transfer discount");
    }

    @Test
    void sameLineSeatChangesMergeBeforeLayerLookupAndRounding() {
        FareTable table = FareTable.EMPTY.withFormula(new FareTable.Formula(2, 0.6, 8, List.of(), 1, 10))
                .withBilling(billing(Set.of("nerdy"), 0, 1, 50));
        FareCalculator calculator = calculator(rails, table);
        List<FareCalculator.RideLeg> split = List.of(ride("a", "s1", "s2", 1, 300),
                ride("a", "s2", "s2", 0, 0), ride("a", "s2", "s4", 2, 600));
        assertEquals(calculator.quoteRide(List.of(ride("a", "s1", "s4", 3, 900))).fare(), calculator.quoteRide(split).fare());
        FareTable pairTable = table.withPair("s1", "s4", new FareTable.Pair(15, true));
        assertEquals(15, calculator(rails, pairTable).quoteRide(split).fare());
        assertEquals(15, calculator(rails, pairTable).quoteRide(List.of(ride("a", "s1", "s2", 1, 300),
                ride("nerdy", "s2", "s2", 0, 0), ride("a", "s2", "s4", 2, 600))).fare(),
                "a stationary free seat hop does not split a through fare");
    }

    @Test
    void stationarySeatHopDoesNotEarnOrCreateTransferDiscount() {
        FareTable table = FareTable.EMPTY.withFormula(new FareTable.Formula(10, 0, 0, List.of(), 0, 0))
                .withBilling(billing(Set.of(), 0, 0, 100));
        FareCalculator calculator = calculator(rails, table);
        assertEquals(10, calculator.quoteRide(List.of(ride("b", "s1", "s1", 0, 0), ride("a", "s1", "s2", 1, 300))).fare(),
                "a stationary boarding neither costs extra nor makes the first real ride a free transfer");
        assertEquals(10, calculator.quoteRide(List.of(ride("a", "s1", "s2", 1, 300), ride("b", "s2", "s2", 0, 0),
                ride("a", "s2", "s3", 1, 300))).fare(), "a stationary other-line seat neither resets the paid line nor splits the through fare");
        assertEquals(3, calculator(rails, table.withBilling(billing(Set.of(), 3, 0, 100)))
                        .quoteRide(List.of(ride("a", "s1", "s1", 0, 0), ride("b", "s1", "s1", 0, 0))).fare(),
                "boarding and leaving paid trains without travelling still pays the journey minimum, once");
    }

    @Test
    void journeyMinimumAppliesOnceAfterDiscountsAndNeverToFreeOnlyRides() {
        FareTable table = FareTable.EMPTY.withFormula(new FareTable.Formula(3, 0, 0, List.of(), 0, 8))
                .withBilling(billing(Set.of("nerdy"), 12, 100, 100));
        FareCalculator calculator = calculator(rails, table);
        List<FareCalculator.RideLeg> legs = List.of(ride("a", "s1", "s2", 1, 300), ride("b", "s2", "s3", 1, 300));
        assertEquals(12, calculator.quoteRide(legs).fare(), "discount is capped at zero, then billing floor applied once");
        assertEquals(8, calculator(rails, table.withBilling(billing(Set.of(), 0, 100, 100))).quoteRide(legs).fare(),
                "formula minimum also floors the total just once, not every leg");
        FareTable noDiscount = table.withBilling(billing(Set.of(), 0, 0, 0));
        assertEquals(8, calculator(rails, noDiscount).quoteRide(legs).fare(), "two raw fares of 3 still have just one floor of 8");
        FareTable pairs = table.withPair("s1", "s2", new FareTable.Pair(0, false)).withPair("s2", "s3", new FareTable.Pair(0, false));
        assertEquals(12, calculator(rails, pairs).quoteRide(legs).fare(), "billing minimum also covers pair overrides");
        assertEquals(0, calculator.quoteRide(List.of(ride("nerdy", "s1", "s3", 2, 600))).fare());
    }

    @Test
    void sameStationPaidRidesHaveMinimumAndLoopDistanceIsPreserved() {
        FareTable table = FareTable.EMPTY.withFormula(new FareTable.Formula(1, 1, 0, List.of(), 0, 4))
                .withBilling(billing(Set.of("nerdy"), 5, 0, 0));
        FareCalculator calculator = calculator(rails, table);
        assertEquals(5, calculator.quoteRide(List.of(ride("a", "s1", "s1", 0, 0))).fare());
        assertEquals(7, calculator.quoteRide(List.of(ride("a", "s1", "s1", 6, 1800))).fare());
        assertEquals(7, calculator.quoteRide(List.of(ride("a", "s1", "s4", 3, 900), ride("a", "s4", "s1", 3, 900))).fare());
        assertEquals(0, calculator.quoteRide(List.of(ride("nerdy", "s1", "s1", 6, 1800))).fare());
        assertEquals(0, calculator.quote("s1", "s1").orElseThrow().fare(), "a static same-station quote implies no observed ride");
        assertEquals(5, calculator(rails, table.withPair("s1", "s1", new FareTable.Pair(0, true)))
                .quoteRide(List.of(ride("a", "s1", "s1", 6, 1800))).fare());
    }

    @Test
    void plannedQuotesUsePaidLegsAndIgnoreWalkingWhenNewRulesApply() {
        FareTable table = FareTable.EMPTY.withFormula(new FareTable.Formula(10, 0, 2, List.of(), 0, 0))
                .withBilling(billing(Set.of("nerdy"), 0, 1, 50));
        assertEquals(15, calculator(rails, table).quote("s1", "s5").orElseThrow().fare());
        assertEquals(10, calculator(rails, table.withBilling(billing(Set.of("a"), 0, 1, 50)))
                .quote("s1", "s5").orElseThrow().fare(), "a free leg does not earn a paid transfer discount");
        assertEquals(0, calculator(withWalk, table.withBilling(billing(Set.of(), 20, 0, 0)))
                .quote("s3", "s5").orElseThrow().fare(), "walking alone is not a paid ride");
        assertEquals(20, calculator(withWalk, table.withBilling(billing(Set.of(), 20, 0, 0)))
                .quote("s1", "s5").orElseThrow().fare(), "paid ride then walk retains the paid minimum");
    }

    /** Flat 3, 1.5 a stop, 2.5 a change; s1 s2 in zone 1, s3 in zone 2, s4 s5 in zone 3, crossing a boundary costs extra. */
    private static FareTable zoned(FareTable.Pricing pricing, double minimum) {
        return FareTable.EMPTY.withFormula(new FareTable.Formula(3, 1.5, 2.5, List.of(), 0, minimum))
                .withZone("s1", "1").withZone("s2", "1").withZone("s3", "2").withZone("s4", "3").withZone("s5", "3")
                .withZoneFare("1", "2", 1.5).withZoneFare("2", "3", 1.0).withZoneFare("1", "3", 0.5)
                .withBilling(billing(Set.of("nerdy"), 0, 0, 0)).withPricing(pricing);
    }

    private static FareCalculator.RideLeg ride(String line, int stops, String... path) {
        return new FareCalculator.RideLeg(line, path[0], path[path.length - 1], stops, 300.0 * (path.length - 1), List.of(path));
    }

    @Test
    void cumulativePricingAddsEveryPartOfTheRouteRidden() {
        FareCalculator legacy = calculator(rails, zoned(FareTable.Pricing.LEGACY, 5));
        FareCalculator cumulative = calculator(rails, zoned(FareTable.Pricing.CUMULATIVE, 5));
        FareCalculator.RideLeg s1ToS4 = ride("a", 3, "s1", "s2", "s3", "s4");
        assertEquals(0.5, legacy.quoteRide(List.of(s1ToS4)).fare(), "the old way: the 1-3 zone fare replaces everything");
        FareCalculator.Quote quote = cumulative.quoteRide(List.of(s1ToS4));
        assertEquals(10, quote.fare(), "base 3 + 3 stops 4.5 + crossing 1-2 1.5 + crossing 2-3 1.0: " + quote.reasoning());
        assertEquals(FareCalculator.Layer.COMBINED, quote.layer());
        assertTrue(quote.reasoning().contains("Zone 1-2: 1 × $1.50 = $1.50"), quote.reasoning()::toString);
        assertTrue(quote.reasoning().contains("Total: $10"), quote.reasoning()::toString);
        assertFalse(quote.reasoning().stream().anyMatch(line -> line.startsWith("Subtotal")), "nothing to adjust: " + quote.reasoning());
        assertTrue(quote.reasoning().contains("Route (a): Station S1 → Station S2 → Station S3 → Station S4"), quote.reasoning()::toString);

        // Out and back again is the whole trip, not "s1 to s1": 4 stops and two crossings of the 1-2 boundary.
        assertEquals(12, cumulative.quoteRide(List.of(ride("a", 2, "s1", "s2", "s3"), ride("a", 2, "s3", "s2", "s1"))).fare());
        // A change to line b adds its stop and the change, and costs no zone fare inside zone 3.
        assertEquals(14, cumulative.quoteRide(List.of(s1ToS4, ride("b", 1, "s4", "s5"))).fare());
        assertEquals(5, cumulative.quoteRide(List.of(ride("a", 0, "s1"))).fare(), "boarding and leaving still costs the minimum");
        assertEquals(0, cumulative.quoteRide(List.of(ride("nerdy", 3, "s1", "s2", "s3", "s4"))).fare(), "free lines stay free");
        assertEquals(13, calculator(rails, zoned(FareTable.Pricing.CUMULATIVE, 5).withBilling(billing(Set.of(), 0, 1, 0)))
                .quoteRide(List.of(s1ToS4, ride("b", 1, "s4", "s5"))).fare(), "the transfer discount comes off the second line's part");
    }

    @Test
    void highestLowestAndCustomRulesCombineTheParts() {
        FareCalculator.RideLeg s1ToS4 = ride("a", 3, "s1", "s2", "s3", "s4");
        // formula 3 + 4.5 = 7.5; zones along the way 2.5
        assertEquals(7.5, calculator(rails, zoned(FareTable.Pricing.HIGHEST, 0)).quoteRide(List.of(s1ToS4)).fare());
        assertEquals(2.5, calculator(rails, zoned(FareTable.Pricing.LOWEST, 0)).quoteRide(List.of(s1ToS4)).fare());
        assertEquals(5, calculator(rails, zoned(FareTable.Pricing.LOWEST, 5)).quoteRide(List.of(s1ToS4)).fare(), "the minimum is still a floor");
        FareCalculator.RideLeg inZone = ride("a", 1, "s1", "s2");
        assertEquals(4.5, calculator(rails, zoned(FareTable.Pricing.LOWEST, 0)).quoteRide(List.of(inZone)).fare(),
                "no zone fare inside zone 1 is not a free zone fare");
        assertEquals(0, calculator(rails, zoned(FareTable.Pricing.LOWEST, 0).withPair("s1", "s2", new FareTable.Pair(0, true)))
                .quoteRide(List.of(inZone)).fare(), "a pair set to zero is a price");

        FareTable custom = zoned(FareTable.Pricing.LEGACY, 0).withCalculation("base + max(stops, zones) + changes");
        assertEquals(FareTable.Pricing.CUSTOM, custom.pricing());
        assertEquals(7.5, calculator(rails, custom).quoteRide(List.of(s1ToS4)).fare());
        FareCalculator.Quote capped = calculator(rails, custom.withCalculation("min(10, formula + zones)"))
                .quoteRide(List.of(ride("a", 2, "s1", "s2", "s3"), ride("a", 2, "s3", "s2", "s1")));
        assertEquals(10, capped.fare());
        assertTrue(capped.reasoning().contains("Rule: min(10, formula + zones)"), capped.reasoning()::toString);
        assertThrows(IllegalArgumentException.class, () -> custom.withCalculation("base / stops"));
    }

    @Test
    void pricingAndCalculationRoundTripAndOldFilesStayLegacy() {
        FareCodec codec = new FareCodec();
        FareTable table = zoned(FareTable.Pricing.LEGACY, 5).withCalculation("max(formula, zones) + pairs");
        String yaml = codec.write(table);
        assertTrue(yaml.contains("pricing: custom"), yaml);
        FareCodec.ReadResult read = codec.read(yaml);
        assertTrue(read.clean(), read.errors()::toString);
        assertEquals(table, read.table());
        assertEquals(table.withPricing(FareTable.Pricing.HIGHEST), codec.read(codec.write(table.withPricing(FareTable.Pricing.HIGHEST))).table());
        FareCodec.ReadResult old = codec.read("format: 1\ncurrency: $\nformula:\n  flat: 2\n");
        assertTrue(old.clean(), old.errors()::toString);
        assertEquals(FareTable.Pricing.LEGACY, old.table().pricing());
        FareCodec.ReadResult bad = codec.read("pricing: sideways\ncalculation: base +\n");
        assertEquals(2, bad.errors().size(), bad.errors()::toString);
        assertFalse(bad.table().billing().enabled());
    }

    @Test
    void plannedQuotesFollowTheRouteInRouteAwareModes() {
        FareCalculator calculator = calculator(rails, zoned(FareTable.Pricing.CUMULATIVE, 0));
        FareCalculator.Quote quote = calculator.quote("s1", "s5").orElseThrow();
        // a s1 to s4 (10) then b to s5: a stop 1.5 and a change 2.5
        assertEquals(14, quote.fare(), quote.reasoning()::toString);
        assertTrue(calculator.quote("s1", "nowhere").isEmpty());
    }

    @Test
    void calculationRejectsOverflowAndRoundingDoesNotSaturateLongs() {
        FareTable table = FareTable.EMPTY.withFormula(new FareTable.Formula(0, Double.MAX_VALUE, 0, List.of(), 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> calculator(rails, table).quoteRide(List.of(ride("a", "s1", "s3", 2, 600))));
        FareTable.Formula huge = new FareTable.Formula(0, 0, 0, List.of(), 0.01, 0);
        assertEquals(1e25, huge.finish(1e25));
    }
}
