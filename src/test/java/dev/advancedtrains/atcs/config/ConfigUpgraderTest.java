package dev.advancedtrains.atcs.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigUpgraderTest {
    private static final Logger LOG = Logger.getLogger("test");

    private static String bundled(String name) throws IOException {
        try (InputStream in = ConfigUpgraderTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(in, name + " is bundled");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void anOldFileGainsTheNewOptionsAndKeepsItsOwnValues(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, """
                # my notes
                config-version: 2
                passenger:
                  boss-bar: false
                  sounds:
                    arrival: "minecraft:block.bell.use"
                control:
                  dwell-seconds: 15
                  typo-option: 1
                """);
        ConfigUpgrader.Report report = ConfigUpgrader.upgrade(file, bundled("config.yml"), AtcsSettings.CONFIG_VERSION, LOG);
        assertTrue(report.written());
        assertEquals(2, report.fromVersion());
        assertTrue(report.added().contains("control.even-spacing"), "new options are listed: " + report.added());
        assertTrue(report.added().contains("integrations.editor.port"));
        assertFalse(report.added().contains("control.dwell-seconds"), "an option the file had is not 'added'");
        assertEquals(java.util.List.of("control.typo-option"), report.unknown());
        assertNotNull(report.backup());
        assertTrue(Files.exists(report.backup()), "the old file is kept");
        assertTrue(Files.readString(report.backup()).contains("# my notes"));
        String text = Files.readString(file);
        assertTrue(text.contains("config-version: " + AtcsSettings.CONFIG_VERSION), text.substring(0, 300));
        assertTrue(text.contains("boss-bar: false"), "own values stay");
        assertTrue(text.contains("dwell-seconds: 15"));
        assertTrue(text.contains("arrival: minecraft:block.bell.use") || text.contains("arrival: \"minecraft:block.bell.use\""));
        assertTrue(text.contains("typo-option: 1"), "unknown options are kept");
        assertTrue(text.contains("# Even spacing: a train that is catching up"), "the comments of new options come along");
        assertTrue(text.contains("even-spacing: false"));
        assertTrue(text.indexOf("passenger:") < text.indexOf("control:"), "bundled order");
        assertTrue(report.summary().contains("upgraded from version 2 to " + AtcsSettings.CONFIG_VERSION), report.summary());

        ConfigUpgrader.Report again = ConfigUpgrader.upgrade(file, bundled("config.yml"), AtcsSettings.CONFIG_VERSION, LOG);
        assertFalse(again.written(), "a current file is left alone");
        assertEquals(text, Files.readString(file));
    }

    @Test
    void theBundledFileIsCurrentAndMessagesWork(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, bundled("config.yml"));
        assertFalse(ConfigUpgrader.upgrade(file, bundled("config.yml"), AtcsSettings.CONFIG_VERSION, LOG).written(),
                "the bundled config.yml carries the current version");
        Path messages = dir.resolve("messages.yml");
        Files.writeString(messages, "passenger:\n  next-stop: \"<gray>Next: <station>\"\n");
        ConfigUpgrader.Report report = ConfigUpgrader.upgrade(messages, bundled("messages.yml"), -1, LOG);
        assertTrue(report.written());
        assertEquals(-1, report.fromVersion());
        assertTrue(report.added().size() > 10, "most texts were missing: " + report.added().size());
        String text = Files.readString(messages);
        assertTrue(text.contains("Next: <station>"), "the own text stays");
        assertTrue(text.contains("hologram:"), "the rest is filled in");
        assertEquals(dir.resolve("messages.yml.bak"), report.backup());
        assertFalse(ConfigUpgrader.upgrade(messages, bundled("messages.yml"), -1, LOG).written());
    }

    @Test
    void aBrokenFileIsNotTouched(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "passenger: [unclosed\n");
        ConfigUpgrader.Report report = ConfigUpgrader.upgrade(file, bundled("config.yml"), AtcsSettings.CONFIG_VERSION, LOG);
        assertFalse(report.written());
        assertNotNull(report.problem());
        assertEquals("passenger: [unclosed\n", Files.readString(file));
        assertTrue(report.summary().contains("not valid YAML"));
    }

    @Test
    void aMissingFileIsWrittenFromTheBundle(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config.yml");
        ConfigUpgrader.Report report = ConfigUpgrader.upgrade(file, bundled("config.yml"), AtcsSettings.CONFIG_VERSION, LOG);
        assertTrue(report.written());
        assertEquals(bundled("config.yml"), Files.readString(file));
    }
}
