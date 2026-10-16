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
import java.util.TreeMap;

import static dev.advancedtrains.atcs.TestNetworks.network;
import static dev.advancedtrains.atcs.TestNetworks.service;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
