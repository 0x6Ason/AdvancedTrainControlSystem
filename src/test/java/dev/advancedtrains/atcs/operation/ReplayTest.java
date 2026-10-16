package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.NetworkCodec;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scores the estimator on a quarter of an hour of a real railway (the staging copy of the author's
 * server, 25 trains on four lines, positions twice a second). Prints the scores so a change can be
 * judged, and fails when a model is clearly worse than the plain one it is meant to improve.
 */
class ReplayTest {
    private static final Path FIXTURES = Path.of("src/test/resources/fixtures/staging");

    private static Replay replay() throws IOException {
        NetworkCodec.ReadResult read = new NetworkCodec().read(Files.readString(FIXTURES.resolve("network.yml"), StandardCharsets.UTF_8));
        assertTrue(read.clean(), read.errors()::toString);
        Network network = read.network();
        TravelTimes times = new TravelTimes(0.3);
        times.load(yaml("timings.yml"));
        TrackPaths paths = new TrackPaths();
        paths.load(yaml("track-paths.yml"));
        return new Replay(network, times, paths);
    }

    private static YamlConfiguration yaml(String name) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(FIXTURES.resolve(name), StandardCharsets.UTF_8));
        } catch (org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IOException(exception);
        }
        return yaml;
    }

    @Test
    void modelsScoredOnTheStagingRailway() throws IOException {
        Path events = FIXTURES.resolve("events.jsonl");
        EtaSamples plain = replay().run(events, Replay.Model.PLAIN);
        EtaSamples positions = replay().run(events, Replay.Model.POSITIONS);
        EtaSamples consistent = replay().run(events, Replay.Model.CONSISTENT);
        System.out.println("plain:      " + Replay.summary(plain));
        System.out.println("positions:  " + Replay.summary(positions));
        System.out.println("consistent: " + Replay.summary(consistent));
        assertTrue(plain.sampleCount() > 100, "the fixture holds enough arrivals to mean something");
        for (int horizon : new int[] {120, 60, 30}) {
            assertTrue(Replay.meanAbs(positions, horizon) <= Replay.meanAbs(plain, horizon) * 1.1 + 0.5,
                    "positions must not be clearly worse than plain at " + horizon + " s");
            assertTrue(Replay.meanAbs(consistent, horizon) <= Replay.meanAbs(plain, horizon) * 1.1 + 0.5,
                    "consistent must not be clearly worse than plain at " + horizon + " s");
        }
    }
}
