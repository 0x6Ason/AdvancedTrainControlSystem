package dev.advancedtrains.atcs.display;

import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Platform;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.network.Station;
import dev.advancedtrains.atcs.network.Stop;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayRendererTest {
    private static final int RED = 0xE53935;
    private static final int BLUE = 0x1E88E5;

    private static Network network() {
        Service north = Service.of(ServiceKey.parse("a/north"), "Local",
                List.of(Stop.parse("main:1"), Stop.of("nerdy"), Stop.parse("~fast"), Stop.of("beach"), Stop.of("end")));
        Service shuttle = Service.of(ServiceKey.parse("b/x"), "Shuttle", List.of(Stop.of("nerdy"), Stop.of("beach")));
        Network.Builder builder = Network.builder()
                .station(new Station("main", "Main St.", Map.of("1", Platform.of("1")), null))
                .station(Station.of("nerdy", "Nerdy St."))
                .station(Station.of("fast", "Fast Pass"))
                .station(Station.of("beach", "Golden Beach"))
                .station(Station.of("end", "New Village"))
                .line(Line.of("a", "Line A", RED).withService(north))
                .line(Line.of("b", "Line B", BLUE).withService(shuttle));
        return builder.build();
    }

    private static boolean hasColour(BufferedImage image, int rgb, int x0, int y0, int x1, int y1) {
        for (int y = Math.max(0, y0); y < Math.min(image.getHeight(), y1); y++) {
            for (int x = Math.max(0, x0); x < Math.min(image.getWidth(), x1); x++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) == rgb) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int count(BufferedImage image, int rgb) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) == rgb) {
                    count++;
                }
            }
        }
        return count;
    }

    @Test
    void roadmapDrawsTheLineInItsColourWithYouAreHere() {
        Network network = network();
        Service service = network.service(ServiceKey.parse("a/north")).orElseThrow();
        BufferedImage image = DisplayRenderer.roadmap(network, service, "nerdy", 512, 128);
        assertEquals(512, image.getWidth());
        int lineY = (int) (128 * 0.72);
        assertTrue(hasColour(image, RED, 0, lineY - 2, 512, lineY + 3), "the line runs across in the line colour");
        assertTrue(count(image, DisplayRenderer.HERE.getRGB() & 0xFFFFFF) > 20, "you are here is marked in red");
        assertTrue(hasColour(image, BLUE, 0, lineY, 512, 128), "the interchange shows Line B's badge below the line");
        BufferedImage elsewhere = DisplayRenderer.roadmap(network, service, null, 512, 128);
        assertEquals(0, count(elsewhere, DisplayRenderer.HERE.getRGB() & 0xFFFFFF), "no here marker without a station");
        BufferedImage tiny = DisplayRenderer.roadmap(network, service, "main", 128, 128);
        assertEquals(128, tiny.getHeight(), "a single frame still renders");
    }

    @Test
    void tilesCutTheMosaicAndPadBeyondItsEdge() {
        BufferedImage mosaic = new BufferedImage(256, 128, BufferedImage.TYPE_INT_RGB);
        mosaic.setRGB(200, 10, RED);
        BufferedImage right = DisplayRenderer.tile(mosaic, 1, 0);
        assertEquals(RED, right.getRGB(72, 10) & 0xFFFFFF, "pixel 200 lands at 72 in the second tile");
        BufferedImage beyond = DisplayRenderer.tile(mosaic, 1, 1);
        assertEquals(0xFFFFFF, beyond.getRGB(5, 5) & 0xFFFFFF, "outside the mosaic is paper");
        assertEquals(0, count(beyond, RED));
    }

    @Test
    void boardListsDeparturesUnderADarkHeader() {
        List<DisplayRenderer.BoardRow> rows = List.of(
                new DisplayRenderer.BoardRow("A", RED, "New Village", "2:05", false, "1"),
                new DisplayRenderer.BoardRow("B", BLUE, "Golden Beach", "Boarding", true, null));
        BufferedImage image = DisplayRenderer.board("Main St.", "Platform 1", rows, "No departures", 256, 128);
        int ink = DisplayRenderer.INK.getRGB() & 0xFFFFFF;
        assertEquals(ink, image.getRGB(3, 3) & 0xFFFFFF, "header bar");
        assertTrue(hasColour(image, RED, 0, 20, 60, 128), "first row's badge");
        assertTrue(hasColour(image, BLUE, 0, 20, 60, 128), "second row's badge");
        assertTrue(hasColour(image, DisplayRenderer.GOOD.getRGB() & 0xFFFFFF, 128, 20, 256, 128), "Boarding is green");
        BufferedImage empty = DisplayRenderer.board("Main St.", null, List.of(), "No departures", 128, 128);
        assertFalse(hasColour(empty, RED, 0, 0, 128, 128));
    }

    @Test
    void networkMapFitsTheTrackAndProjectsPositions() {
        List<DisplayRenderer.MapLine> lines = List.of(new DisplayRenderer.MapLine(RED,
                List.of(new double[] {0, 0}, new double[] {100, 0}, new double[] {100, 50})));
        List<DisplayRenderer.MapStation> stations = List.of(
                new DisplayRenderer.MapStation("main", "Main St.", 0, 0, List.of(RED, BLUE), true),
                new DisplayRenderer.MapStation("end", "New Village", 100, 50, List.of(RED), true));
        DisplayRenderer.NetworkMap map = DisplayRenderer.networkMap("", lines, stations, "end", 128, 128);
        assertEquals(128, map.image().getWidth());
        assertTrue(count(map.image(), RED) > 50, "the track is drawn");
        assertTrue(map.projection().covers(50, 25));
        assertFalse(map.projection().covers(-1, 0));
        double px = map.projection().pixelX(100);
        double py = map.projection().pixelY(50);
        assertTrue(px > 64 && px < 128 && py > 32 && py < 128, "the far corner projects to the lower right");
        assertTrue(count(map.image(), DisplayRenderer.HERE.getRGB() & 0xFFFFFF) > 5, "the here station is red");
    }

    @Test
    void statusPanelShowsEveryLine() {
        List<DisplayRenderer.StatusRow> rows = List.of(
                new DisplayRenderer.StatusRow("A", RED, "Line A", null),
                new DisplayRenderer.StatusRow("B", BLUE, "Line B", "Track works"));
        BufferedImage image = DisplayRenderer.status("Service status", rows, 128, 128);
        assertTrue(hasColour(image, DisplayRenderer.GOOD.getRGB() & 0xFFFFFF, 0, 0, 128, 128), "good service in green");
        assertTrue(hasColour(image, DisplayRenderer.BAD.getRGB() & 0xFFFFFF, 0, 0, 128, 128), "a suspension in red");
    }

    @Test
    void logoIsCentredWithACaption() {
        BufferedImage roundel = LogoRenderer.station("MS", List.of(RED), 64);
        BufferedImage image = DisplayRenderer.logo(roundel, "Main St.", 128, 128);
        assertTrue(hasColour(image, RED, 20, 10, 108, 100), "the ring is drawn larger in the middle");
        assertEquals(0xFFFFFF, image.getRGB(1, 1) & 0xFFFFFF, "paper around it");
    }
}
