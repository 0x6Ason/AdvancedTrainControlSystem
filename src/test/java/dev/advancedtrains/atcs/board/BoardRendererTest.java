package dev.advancedtrains.atcs.board;

import dev.advancedtrains.atcs.config.Messages;
import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.Stop;
import dev.advancedtrains.atcs.operation.Departures;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.logging.Logger;

import static dev.advancedtrains.atcs.TestNetworks.network;
import static dev.advancedtrains.atcs.TestNetworks.service;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardRendererTest {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static Messages messages() throws IOException {
        try (InputStreamReader reader = new InputStreamReader(
                Objects.requireNonNull(BoardRendererTest.class.getResourceAsStream("/messages.yml")), StandardCharsets.UTF_8)) {
            return Messages.read(YamlConfiguration.loadConfiguration(reader), Logger.getLogger("test"));
        }
    }

    private static List<String> plain(List<Component> lines) {
        return lines.stream().map(PLAIN::serialize).toList();
    }

    @Test
    void rendersEachDepartureState() throws IOException {
        Network network = network(3, service("a/north", "s1", "s2:2", "s3"));
        Service north = network.service(service("a/north").key()).orElseThrow();
        Messages messages = messages();

        Departures.Departure due = new Departures.Departure("t", north, 1, "2", OptionalDouble.of(95), Departures.Status.EN_ROUTE);
        assertEquals(List.of("A Local", "Station S3", "1:35", "Platform 2"), plain(BoardRenderer.departure(due, network, messages)));

        Departures.Departure arriving = new Departures.Departure("t", north, 1, "2", OptionalDouble.of(9), Departures.Status.EN_ROUTE);
        assertEquals("Arriving", plain(BoardRenderer.departure(arriving, network, messages)).get(2));

        Departures.Departure boarding = new Departures.Departure("t", north, 1, "2", OptionalDouble.of(0), Departures.Status.BOARDING);
        assertEquals("Boarding", plain(BoardRenderer.departure(boarding, network, messages)).get(2));

        Departures.Departure unknown = new Departures.Departure("t", north, 1, null, OptionalDouble.empty(), Departures.Status.EN_ROUTE);
        assertEquals(List.of("A Local", "Station S3", "Expected", "Platform -"), plain(BoardRenderer.departure(unknown, network, messages)));

        assertEquals(List.of("", "No further", "departures", ""), plain(BoardRenderer.empty(messages)));
    }

    /** A loop line's "Counter-clockwise" is wider than a sign line; the board says CCW instead. */
    @Test
    void serviceNamesThatDoNotFitAreShortened() throws IOException {
        Service ccw = Service.of(service("5/ccw").key(), "Counter-clockwise", List.of(Stop.of("s1"), Stop.of("s2"), Stop.of("s3")));
        Service cw = Service.of(service("5/cw").key(), "Clockwise", List.of(Stop.of("s3"), Stop.of("s2"), Stop.of("s1")));
        Service named = Service.of(service("5/x").key(), "Round the island", List.of(Stop.of("s1"), Stop.of("s2"))).withShortName("Island");
        Network network = network(3).toBuilder()
                .line(Line.of("5", "Line 5", 0x8E24AA).withService(ccw).withService(cw).withService(named))
                .build();
        Messages messages = messages();
        Departures.Departure one = new Departures.Departure("t", ccw, 0, "1", OptionalDouble.of(95), Departures.Status.EN_ROUTE);
        List<String> lines = plain(BoardRenderer.departure(one, network, messages));
        assertEquals("5 CCW", lines.get(0), "the first line is shortened: " + lines);
        assertEquals("Station S3", lines.get(1), "the other lines are as before");
        Departures.Departure two = new Departures.Departure("t", cw, 0, "1", OptionalDouble.of(95), Departures.Status.EN_ROUTE);
        assertEquals("5 Clockwise", plain(BoardRenderer.departure(two, network, messages)).get(0), "a name that fits stays");
        Departures.Departure three = new Departures.Departure("t", named, 0, "1", OptionalDouble.of(95), Departures.Status.EN_ROUTE);
        assertEquals("5 Island", plain(BoardRenderer.departure(three, network, messages)).get(0), "a short name set by hand is used");
        assertTrue(SignText.width("Counter-clockwise", false) > SignText.LINE_WIDTH - 11);
        assertEquals(6 + 6 + 2 + 6, SignText.width("Main", false), "an i is narrow");
        assertEquals(2 + 3 + 4 + 4, SignText.width("ilt ", false));
        assertEquals(6 + 6 + 2 + 6 + 4, SignText.width("Main", true), "bold adds a pixel per character");
    }

    @Test
    void suspendedLinesShowOnBoards() throws IOException {
        Network network = network(2, service("a/north", "s1", "s2")).toBuilder()
                .line(Line.of("a", "Line A", 0xFF0000).withService(service("a/north", "s1", "s2")).withSuspension("Works"))
                .build();
        Service north = network.service(service("a/north").key()).orElseThrow();
        Departures.Departure due = new Departures.Departure("t", north, 0, "1", OptionalDouble.of(30), Departures.Status.EN_ROUTE);
        assertEquals("Suspended", plain(BoardRenderer.departure(due, network, messages())).get(2));
    }
}
