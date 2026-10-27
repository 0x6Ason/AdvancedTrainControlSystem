package dev.advancedtrains.atcs.fare;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FareExpressionTest {
    private static double evaluate(String expression, Map<String, Double> components) {
        return FareExpression.compile(expression).evaluate(components);
    }

    @Test
    void addsWhatIsThereAndSkipsWhatIsNot() {
        assertEquals(4.5, evaluate(FareExpression.ADDITIVE, Map.of("base", 3.0, "zones", 1.5)));
        assertEquals(0, evaluate(FareExpression.ADDITIVE, Map.of()), "nothing configured costs nothing");
        assertEquals(4.5, evaluate("sum(base, zones, pairs)", Map.of("base", 3.0, "zones", 1.5)));
    }

    @Test
    void highestAndLowestIgnoreMissingPricesButNotZeroOnes() {
        assertEquals(7, evaluate("min(formula, zones, pairs)", Map.of("formula", 7.0)), "no zone price is not a free zone");
        assertEquals(0, evaluate("min(formula, zones, pairs)", Map.of("formula", 7.0, "zones", 0.0)), "a zero price is a price");
        assertEquals(7, evaluate("max(formula, zones)", Map.of("formula", 7.0, "zones", 2.5)));
        assertEquals(10, evaluate("min(10, formula)", Map.of("formula", 12.0)), "a constant caps");
    }

    @Test
    void arithmeticFollowsTheUsualOrder() {
        Map<String, Double> components = Map.of("base", 1.0, "stops", 2.0);
        assertEquals(5, evaluate("base + 2 * stops", components));
        assertEquals(1.5, evaluate("(base + stops) / 2", components));
        assertEquals(1, evaluate("base + 2 * zones", components), "a product with a missing price is missing");
        assertEquals(0, evaluate("base - 10", components), "never below zero");
        assertEquals(4, evaluate("  max( base ,stops )*1.5+ base  ", components));
    }

    @Test
    void rejectsAnythingButPricesAndArithmetic() {
        for (String bad : new String[]{"", "   ", "base +", "base stops", "fare", "base / stops", "base / 0", "base )", "max(",
                "max()", "1.2.3", "Runtime.exec(1)", "base;stops", "x".repeat(513), "(".repeat(30) + "base" + ")".repeat(30),
                "1+".repeat(100) + "1"}) {
            assertThrows(IllegalArgumentException.class, () -> FareExpression.compile(bad), bad);
        }
    }
}
