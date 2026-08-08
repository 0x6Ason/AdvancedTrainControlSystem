package dev.advancedtrains.atcs.legacy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaSpawnPatternAnalyzerTest {
    @Test
    void countsTheServersRegularMinecartPatterns() {
        assertEquals(12, VanillaSpawnPatternAnalyzer.regularMinecartCount("12m").orElseThrow());
        assertEquals(9, VanillaSpawnPatternAnalyzer.regularMinecartCount("9m").orElseThrow());
        assertEquals(3, VanillaSpawnPatternAnalyzer.regularMinecartCount("mmm").orElseThrow());
        assertEquals(5, VanillaSpawnPatternAnalyzer.regularMinecartCount("3m 2m").orElseThrow());
    }

    @Test
    void refusesToGuessComplexOrSavedTrainPatterns() {
        assertTrue(VanillaSpawnPatternAnalyzer.regularMinecartCount("MySavedTrain").isEmpty());
        assertTrue(VanillaSpawnPatternAnalyzer.regularMinecartCount("3[50%m 50%s]").isEmpty());
    }
}

