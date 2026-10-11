package dev.advancedtrains.atcs.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static YamlConfiguration bundled(String name) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(
                Objects.requireNonNull(ConfigTest.class.getResourceAsStream("/" + name)), StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    @Test
    void bundledConfigReadsWithoutWarnings() throws IOException {
        AtcsSettings settings = AtcsSettings.read(bundled("config.yml"));
        assertTrue(settings.warnings().isEmpty(), settings.warnings()::toString);
        assertEquals(AtcsSettings.CONFIG_VERSION, bundled("config.yml").getInt("config-version"));
        assertEquals(60, settings.passenger().approachDistance());
        assertNotNull(settings.passenger().arrivalSound());
        assertNull(settings.passenger().departureSound(), "an empty sound key disables the sound");
        assertTrue(settings.blueMap().recordTrackPaths());
    }

    @Test
    void invalidValuesFallBackWithWarnings() throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                passenger:
                  enabled: maybe
                  approach-distance: -5
                  unassigned-trains: SOMETIMES
                  sounds:
                    arrival: "Not A Key!"
                    category: loud
                boards:
                  refresh-ticks: 1
                """);
        AtcsSettings settings = AtcsSettings.read(config);
        assertTrue(settings.passenger().enabled());
        assertEquals(60, settings.passenger().approachDistance());
        assertEquals(AtcsSettings.UnassignedTrains.TITLE, settings.passenger().unassignedTrains());
        assertNull(settings.passenger().arrivalSound());
        assertEquals(20, settings.boards().refreshTicks());
        assertEquals(6, settings.warnings().size(), settings.warnings()::toString);
    }

    @Test
    void messagesRenderWithDefaultsAndPlaceholders() throws IOException {
        YamlConfiguration custom = new YamlConfiguration();
        custom.set("passenger.approaching", "<red>Soon: <next>");
        custom.setDefaults(bundled("messages.yml"));
        custom.options().copyDefaults(true);
        Messages messages = Messages.read(custom, Logger.getLogger("test"));

        assertEquals("Soon: Main St.", PLAIN.serialize(messages.render("passenger.approaching",
                Placeholder.unparsed("next", "Main St."))));
        assertTrue(messages.has("passenger.next-station"), "keys missing from the server file come from the bundle");
        assertEquals(4, messages.renderLines("board.departure").size());
        assertEquals("45s", PLAIN.serialize(messages.duration(44.6)));
        assertEquals("2:05", PLAIN.serialize(messages.duration(125)));
        assertEquals(Component.empty(), messages.render("passenger.unassigned-arrival-subtitle"));
    }

    @Test
    void stationNamesCannotInjectFormatting() throws IOException {
        YamlConfiguration config = new YamlConfiguration();
        config.setDefaults(bundled("messages.yml"));
        config.options().copyDefaults(true);
        Messages messages = Messages.read(config, Logger.getLogger("test"));
        Component rendered = messages.render("passenger.approaching", Placeholder.unparsed("next", "<red>Evil</red>"));
        assertTrue(PLAIN.serialize(rendered).contains("<red>Evil</red>"));
    }
}
