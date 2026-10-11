package dev.advancedtrains.atcs.board;

import dev.advancedtrains.atcs.config.Messages;
import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Service;
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
