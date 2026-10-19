package dev.advancedtrains.atcs.display;

import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Platform;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.network.Station;
import dev.advancedtrains.atcs.network.Stop;
import dev.advancedtrains.atcs.operation.Departures;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The split-flap board, the roadmap and the station sign, and the map-colour pictures they are kept in. */
class RedesignedDisplaysTest {
    /** Line A is green here, so its line is never taken for the red "You are here". */
    private static final int RED = 0x43A047;
    private static final int BLUE = 0x1E88E5;

    private static Network network() {
        Service north = Service.of(ServiceKey.parse("a/north"), "Local",
                List.of(Stop.parse("main:1"), Stop.of("nerdy"), Stop.parse("~fast"), Stop.of("beach"), Stop.of("end")));
        Service shuttle = Service.of(ServiceKey.parse("b/x"), "Shuttle", List.of(Stop.of("nerdy"), Stop.of("beach")));
        Service loop = Service.of(ServiceKey.parse("b/cw"), "Clockwise", List.of(Stop.of("nerdy"), Stop.of("beach"), Stop.of("end")))
                .withCircular(true);
        return Network.builder()
                .station(new Station("main", "Main St.", Map.of("1", Platform.of("1")), null))
                .station(Station.of("nerdy", "Nerdy St."))
                .station(Station.of("fast", "Fast Pass"))
                .station(Station.of("beach", "Golden Beach"))
                .station(Station.of("end", "New Village"))
                .line(Line.of("a", "Line A", RED).withService(north))
                .line(Line.of("b", "Line B", BLUE).withService(shuttle).withService(loop))
                .build();
    }

    private static int count(BufferedImage image, int rgb, int x0, int y0, int x1, int y1) {
        int count = 0;
        for (int y = Math.max(0, y0); y < Math.min(image.getHeight(), y1); y++) {
            for (int x = Math.max(0, x0); x < Math.min(image.getWidth(), x1); x++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) == rgb) {
                    count++;
                }
            }
        }
        return count;
    }

    /** The map colour a drawing's colour turns into. */
    private static int shown(int rgb) {
        return MapPixels.rgb(MapPixels.nearest(rgb));
    }

    @Test
    void picturesAreKeptInMapColoursAndCutIntoTiles() {
        BufferedImage drawing = new BufferedImage(200, 130, BufferedImage.TYPE_INT_ARGB);
        drawing.setRGB(150, 5, 0xFFFFFFFF);
        drawing.setRGB(10, 129, 0xFF000000 | 0x7FB238);
        MapPixels pixels = MapPixels.of(drawing);
        assertEquals(0xFFFFFF, MapPixels.rgb(pixels.get(150, 5)), "white is a map colour of its own");
        assertEquals(0x7FB238, MapPixels.rgb(pixels.get(10, 129)), "so is grass green");
        assertEquals(0, pixels.get(0, 0), "transparent stays transparent");
        byte[] second = pixels.tile(1, 0);
        assertEquals(MapPixels.TILE * MapPixels.TILE, second.length);
        assertEquals(pixels.get(150, 5), second[5 * MapPixels.TILE + 22], "pixel 150 is pixel 22 of the second tile");
        assertEquals(0, pixels.tile(1, 1)[0], "outside the picture is transparent");
        MapPixels small = new MapPixels(4, 4);
        small.blit(pixels, -150, -5);
        assertEquals(pixels.get(150, 5), small.get(0, 0), "a blit cuts what falls outside");
        assertEquals(pixels, MapPixels.of(pixels.toImage()), "the colours shown turn into the same map colours");
    }

    @Test
    void theBoardIsAssembledFromFlapsDrawnOnce() {
        SplitFlapRenderer.Cache cache = new SplitFlapRenderer.Cache();
        MapPixels scene = SplitFlapRenderer.scene("Main St.", "Platform 1", 5, 512, 256);
        List<SplitFlapRenderer.Row> rows = List.of(
                new SplitFlapRenderer.Row("NOW", "A", RED, "New Village St.", "1", SplitFlapRenderer.Remark.BOARDING),
                new SplitFlapRenderer.Row("4 MIN", "B", BLUE, "Golden Beach", "2", SplitFlapRenderer.Remark.NONE));
        MapPixels board = SplitFlapRenderer.board(cache, scene, "14:05", rows, "No departures");
        int flaps = cache.size();
        assertTrue(flaps > 10, "the letters, the line flaps and the blanks are drawn once each: " + flaps);
        assertEquals(board, SplitFlapRenderer.board(cache, scene, "14:05", rows, "No departures"), "the same rows, the same picture");
        assertEquals(flaps, cache.size(), "and nothing is drawn again");
        MapPixels later = SplitFlapRenderer.board(cache, scene, "14:06", rows, "No departures");
        assertNotEquals(board, later, "the clock moved on");
        BufferedImage image = board.toImage();
        assertTrue(count(image, shown(RED), 0, 0, 512, 256) > 30, "line A's flap is in its colour");
        assertTrue(count(image, shown(BLUE), 0, 0, 512, 256) > 30, "line B's flap is in its colour");
        assertEquals(shown(SplitFlapRenderer.HOUSING.getRGB() & 0xFFFFFF), image.getRGB(2, 2) & 0xFFFFFF, "the housing");
        assertEquals(SplitFlapRenderer.MAX_ROWS, SplitFlapRenderer.rows(768, 512), "a tall board shows at most seven rows");
        assertTrue(SplitFlapRenderer.rows(256, 128) >= 3, "a board one frame high still shows three or more");
    }

    @Test
    void flapsTurnThroughTheDrumToTheNewDepartures() {
        List<SplitFlapRenderer.Row> before = List.of(
                new SplitFlapRenderer.Row("NOW", "A", RED, "New Village", "1", SplitFlapRenderer.Remark.BOARDING));
        List<SplitFlapRenderer.Row> after = List.of(
                new SplitFlapRenderer.Row("3 MIN", "B", BLUE, "Golden Beach", "2", SplitFlapRenderer.Remark.NONE));
        List<SplitFlapRenderer.Row> start = SplitFlapRenderer.between(before, after, 0, 768, 256);
        assertTrue(start.get(0).destination().startsWith("NEW"), "at step 0 the old destination still shows");
        List<SplitFlapRenderer.Row> middle = SplitFlapRenderer.between(before, after, 2, 768, 256);
        assertNotEquals(SplitFlapRenderer.flapText("Golden Beach"), middle.get(0).destination().strip(), "still turning");
        List<SplitFlapRenderer.Row> done = SplitFlapRenderer.between(before, after, SplitFlapRenderer.steps(), 768, 256);
        assertEquals("GOLDEN BEACH", done.get(0).destination().strip());
        assertEquals("3 MIN", done.get(0).due().strip());
        assertEquals("B", done.get(0).line());
    }

    @Test
    void roadmapsGoEitherWayAndMarkWhereYouAre() {
        Network network = network();
        Service north = network.service(ServiceKey.parse("a/north")).orElseThrow();
        int red = shown(RoadmapRenderer.RED.getRGB() & 0xFFFFFF);
        BufferedImage right = MapPixels.of(RoadmapRenderer.draw(network, north, "nerdy", RoadmapRenderer.Flow.RIGHT, 768, 128)).toImage();
        BufferedImage left = MapPixels.of(RoadmapRenderer.draw(network, north, "nerdy", RoadmapRenderer.Flow.LEFT, 768, 128)).toImage();
        assertTrue(count(right, red, 0, 0, 768, 128) > 30, "you are here is marked in red");
        assertTrue(count(right, shown(RED), 0, 0, 768, 128) > 100, "the line in its colour");
        assertTrue(count(right, shown(BLUE), 0, 0, 768, 128) > 10, "Line B's bullet at the interchange");
        assertTrue(count(right, red, 0, 0, 384, 128) > count(right, red, 384, 0, 768, 128),
                "Nerdy St. is the second stop: near the start, on the left when trains leave to the right");
        assertTrue(count(left, red, 384, 0, 768, 128) > count(left, red, 0, 0, 384, 128), "and on the right when they leave to the left");
        BufferedImage none = MapPixels.of(RoadmapRenderer.draw(network, north, null, RoadmapRenderer.Flow.RIGHT, 768, 128)).toImage();
        assertEquals(0, count(none, red, 0, 0, 768, 128), "no marker without a station");
        for (RoadmapRenderer.Shape shape : RoadmapRenderer.Shape.values()) {
            BufferedImage wall = RoadmapRenderer.draw(network, north, "beach", RoadmapRenderer.Flow.RIGHT, shape, 512, 384);
            assertEquals(512, wall.getWidth());
            assertTrue(count(MapPixels.of(wall).toImage(), shown(RED), 0, 0, 512, 384) > 50, shape + " draws the line");
        }
        Service loop = network.service(ServiceKey.parse("b/cw")).orElseThrow();
        BufferedImage ring = MapPixels.of(RoadmapRenderer.draw(network, loop, "beach", RoadmapRenderer.Flow.RIGHT, 768, 128)).toImage();
        assertTrue(count(ring, shown(BLUE), 0, 0, 768, 128) > 100, "a loop is drawn too");
    }

    @Test
    void stationSignsComeInTwoManners() {
        List<StationSignRenderer.LineMark> lines = List.of(new StationSignRenderer.LineMark("A", RED, "Line A"),
                new StationSignRenderer.LineMark("B", BLUE, "Line B"));
        BufferedImage newYork = MapPixels.of(StationSignRenderer.draw("Main St.", lines, null, StationSignRenderer.Style.MTA, 1280, 128)).toImage();
        assertEquals(shown(StationSignRenderer.BLACK.getRGB() & 0xFFFFFF), newYork.getRGB(2, 60) & 0xFFFFFF, "black");
        assertEquals(0xFFFFFF, newYork.getRGB(640, (int) (128 * 0.1) + 1) & 0xFFFFFF, "under a white stripe");
        assertTrue(count(newYork, shown(RED), 1000, 0, 1280, 128) > 100, "with the bullets at the end");
        BufferedImage hongKong = MapPixels.of(StationSignRenderer.draw("Main St.", lines, null, StationSignRenderer.Style.MTR, 384, 128)).toImage();
        assertTrue(count(hongKong, shown(RED), 0, 96, 192, 128) > 200, "a band in line A's colour");
        assertTrue(count(hongKong, shown(BLUE), 192, 96, 384, 128) > 200, "and line B's");
        BufferedImage emblem = StationSignRenderer.draw("Nerdy Estate BLK. 1", lines, null, StationSignRenderer.Style.MTA, 128, 128);
        assertEquals(128, emblem.getWidth(), "a long name fits one frame");
    }

    @Test
    void aDepartureBecomesARowOfFlaps() {
        Network network = network();
        Service north = network.service(ServiceKey.parse("a/north")).orElseThrow();
        Line line = network.line("a").orElseThrow();
        SplitFlapRenderer.Row boarding = DisplayService.row(new Departures.Departure("t", north, 1, "2", OptionalDouble.of(0),
                Departures.Status.BOARDING), line, network);
        assertEquals("NOW", boarding.due());
        assertEquals(SplitFlapRenderer.Remark.BOARDING, boarding.remark());
        assertEquals("New Village", boarding.destination());
        assertEquals("2", boarding.platform());
        SplitFlapRenderer.Row soon = DisplayService.row(new Departures.Departure("t", north, 1, "2", OptionalDouble.of(130),
                Departures.Status.EN_ROUTE), line, network);
        assertEquals("3 MIN", soon.due(), "minutes, rounded up");
        assertEquals(SplitFlapRenderer.Remark.NONE, soon.remark());
        SplitFlapRenderer.Row late = DisplayService.row(new Departures.Departure("t", north, 1, null, OptionalDouble.of(400),
                Departures.Status.EN_ROUTE, false, true), line, network);
        assertEquals(SplitFlapRenderer.Remark.DELAYED, late.remark());
        SplitFlapRenderer.Row guess = DisplayService.row(new Departures.Departure("t", north, 1, null, OptionalDouble.of(400),
                Departures.Status.EN_ROUTE, true, false), line, network);
        assertEquals("~7 MIN", guess.due(), "a guess from track distance is marked");
        SplitFlapRenderer.Row suspended = DisplayService.row(new Departures.Departure("t", north, 1, null, OptionalDouble.of(60),
                Departures.Status.EN_ROUTE), line.withSuspension("Works"), network);
        assertEquals(SplitFlapRenderer.Remark.SUSPENDED, suspended.remark());
        assertFalse(suspended.due().contains("MIN"));
    }
}
