package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.ServiceKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EtaSamplesTest {
    private static final Instant T0 = Instant.parse("2026-10-16T10:00:00Z");
    private static final ServiceKey KEY = ServiceKey.parse("a/north");

    @Test
    void scoresTheFirstEstimateUnderEachHorizon() {
        EtaSamples samples = new EtaSamples();
        samples.predicted("t", KEY, 2, 100, T0);                    // under 120 and 600 and 300: arrival predicted at +100
        samples.predicted("t", KEY, 2, 55, T0.plusSeconds(45));     // under 60: predicted at +100 as well
        samples.predicted("t", KEY, 2, 25, T0.plusSeconds(80));     // under 30: predicted at +105
        samples.predicted("t", KEY, 2, 10, T0.plusSeconds(100));    // under 15: predicted at +110
        Map<Integer, Double> errors = samples.arrived("t", KEY, 2, T0.plusSeconds(110));
        assertEquals(10.0, errors.get(120), 0.01, "the two-minute estimate was 10 s early");
        assertEquals(10.0, errors.get(60), 0.01);
        assertEquals(5.0, errors.get(30), 0.01);
        assertEquals(0.0, errors.get(15), 0.01);
        assertEquals(1, samples.stat(KEY, 2, 120).count());
        assertEquals(10.0, samples.stat(KEY, 2, 120).meanAbs(), 0.01);
        assertEquals(10.0, samples.stat(KEY, 2, 120).bias(), 0.01, "positive bias: trains arrive later than said");
        assertEquals(List.of(KEY), samples.services());
        assertEquals(List.of(2), samples.stops(KEY));
        assertEquals(4, samples.worst(KEY).size(), "one entry per horizon whose estimate missed by 10 s or more");
        assertTrue(samples.arrived("t", KEY, 2, T0.plusSeconds(200)).isEmpty(), "scored only once");
    }

    @Test
    void overallCombinesStopsAndSavesAndLoads() {
        EtaSamples samples = new EtaSamples();
        samples.predicted("t", KEY, 1, 50, T0);
        samples.arrived("t", KEY, 1, T0.plusSeconds(56));
        samples.predicted("t", KEY, 2, 50, T0.plusSeconds(60));
        samples.arrived("t", KEY, 2, T0.plusSeconds(104));
        EtaSamples.Stat overall = samples.overall(KEY, 60);
        assertEquals(2, overall.count());
        assertEquals(6.0, overall.meanAbs(), 0.01);
        assertEquals(0.0, overall.bias(), 0.01);
        assertEquals(6.0, overall.maxAbs(), 0.01);

        YamlConfiguration yaml = new YamlConfiguration();
        samples.save(yaml);
        EtaSamples loaded = new EtaSamples();
        loaded.load(yaml);
        assertEquals(2, loaded.overall(KEY, 60).count());
        assertEquals(6.0, loaded.stat(KEY, 1, 60).meanAbs(), 0.01);
        assertEquals(-6.0, loaded.stat(KEY, 2, 60).bias(), 0.01);
        assertEquals(samples.sampleCount(), loaded.sampleCount());
    }

    @Test
    void forgetsTrainsAndStaleStops() {
        EtaSamples samples = new EtaSamples();
        samples.predicted("t", KEY, 1, 50, T0);
        samples.forget("t");
        assertTrue(samples.arrived("t", KEY, 1, T0.plusSeconds(50)).isEmpty());
        samples.predicted("t", KEY, 3, 50, T0);
        samples.predicted("t", KEY, 4, 500, T0);
        samples.arrived("t", KEY, 3, T0.plusSeconds(50));
        samples.rename("t", "u");
        assertEquals(1, samples.arrived("u", KEY, 4, T0.plusSeconds(500)).size(), "the later stop's sample survives a rename");
    }
}
