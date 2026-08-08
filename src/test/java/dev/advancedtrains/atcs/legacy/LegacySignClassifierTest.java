package dev.advancedtrains.atcs.legacy;

import dev.advancedtrains.atcs.domain.BlockPosition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacySignClassifierTest {
    private static final BlockPosition POSITION = new BlockPosition("world", -1, 64, 32);
    private final LegacySignClassifier classifier = new LegacySignClassifier();

    @Test
    void parsesTwelveCartSpawnerAndLaunchForce() {
        LegacyTrainCartsSign sign = classify("[+train]", "spawn 9999", "12m", "");

        assertEquals(LegacySignType.SPAWN, sign.type());
        assertEquals("9999", sign.attributes().get("launch_force"));
        assertEquals("12m", sign.attributes().get("spawn_pattern"));
        assertEquals("12", sign.attributes().get("regular_minecart_count"));
        assertEquals(-1, sign.chunkX());
        assertEquals(2, sign.chunkZ());
    }

    @Test
    void parsesNineCartSpawnerWithoutTreatingMAsMinutes() {
        LegacyTrainCartsSign sign = classify("[+train]", "spawn 9999", "9m", "");

        assertEquals("9", sign.attributes().get("regular_minecart_count"));
        assertTrue(!sign.attributes().containsKey("interval"));
    }

    @Test
    void parsesStandardStationDistanceAndInstruction() {
        LegacyTrainCartsSign sign = classify("[+train]", "station 5", "", "continue");

        assertEquals(LegacySignType.STATION, sign.type());
        assertEquals("5", sign.attributes().get("launch_distance"));
        assertEquals("continue", sign.attributes().get("instruction"));
    }

    @Test
    void ignoresNonTrainCartsSigns() {
        assertTrue(classifier.classify(
                POSITION, "FRONT", List.of("Welcome", "station 5", "", "continue")).isEmpty());
    }

    private LegacyTrainCartsSign classify(String first, String second, String third, String fourth) {
        return classifier.classify(POSITION, "FRONT", List.of(first, second, third, fourth)).orElseThrow();
    }
}

