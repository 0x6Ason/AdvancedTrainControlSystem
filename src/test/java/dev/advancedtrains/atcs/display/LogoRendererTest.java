package dev.advancedtrains.atcs.display;

import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Station;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static dev.advancedtrains.atcs.TestNetworks.network;
import static dev.advancedtrains.atcs.TestNetworks.service;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogoRendererTest {

    private static int rgb(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) & 0xFFFFFF;
    }

    @Test
    void roundelRingShowsEachLineColour() {
        BufferedImage image = LogoRenderer.station("gb", List.of(0xE53935, 0x1E88E5), 64);
        assertEquals(64, image.getWidth());
        // Top of the ring: the first colour (the arc from 12 o'clock clockwise); bottom: the second.
        assertEquals(0xE53935, rgb(image, 40, 7), "upper right of the ring is the first line");
        assertEquals(0x1E88E5, rgb(image, 24, 57), "lower left of the ring is the second line");
        assertEquals(0xFFFFFF, rgb(image, 32, 20), "white inside the ring");
        assertEquals(0, image.getRGB(1, 1) >>> 24, "transparent corner");
        BufferedImage none = LogoRenderer.station("x", List.of(), 32);
        assertEquals(0x9E9E9E, rgb(none, 16, 4), "a grey ring when no line calls");
    }

    @Test
    void badgeUsesTheLineColourAndReadableText() {
        BufferedImage dark = LogoRenderer.lineBadge("A", 0x1E88E5, 32);
        assertEquals(0x1E88E5, rgb(dark, 4, 16));
        assertEquals(java.awt.Color.WHITE, LogoRenderer.onColor(0x1E88E5));
        assertEquals(0x1F2328, LogoRenderer.onColor(0xFFEB3B).getRGB() & 0xFFFFFF, "dark text on yellow");
    }

    @Test
    void svgCarriesTheSameDescription() {
        String svg = LogoRenderer.stationSvg("gb", List.of(0xE53935, 0x1E88E5), 48);
        assertTrue(svg.startsWith("<svg"));
        assertTrue(svg.contains("#e53935") && svg.contains("#1e88e5"), svg);
        assertTrue(svg.contains(">GB<"), svg);
        String single = LogoRenderer.stationSvg("m", List.of(0x006400), 48);
        assertTrue(single.contains("<circle") && single.contains("#006400"), single);
        assertTrue(LogoRenderer.lineBadgeSvg("5", 0x006400, 24).contains(">5<"));
    }

    @Test
    void overridesReplaceGeneratedLogosAndChangeTheKey(@TempDir Path folder) throws Exception {
        Network network = network(2, service("a/x", "s1", "s2"));
        Station station = network.station("s1").orElseThrow();
        Logos logos = new Logos(folder, Logger.getLogger("test"));
        String before = logos.stationKey(network, station, 40);
        assertFalse(logos.stationOverridden("s1"));
        BufferedImage generated = logos.station(network, station, 40);
        assertEquals(40, generated.getWidth());
        BufferedImage own = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                own.setRGB(x, y, 0xFF00FF00);
            }
        }
        Files.createDirectories(folder);
        ImageIO.write(own, "png", folder.resolve("s1.png").toFile());
        assertTrue(logos.stationOverridden("s1"));
        BufferedImage used = logos.station(network, station, 40);
        assertEquals(0x00FF00, rgb(used, 20, 20), "the override, scaled up");
        assertNotEquals(before, logos.stationKey(network, station, 40));
        Line line = network.line("a").orElseThrow();
        assertEquals(line.color(), rgb(logos.line(line, 24), 3, 12));
        assertTrue(logos.lineKey(line, 24).startsWith("line:a:24:"));
    }
}
