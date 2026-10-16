package dev.advancedtrains.atcs.network;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayTest {
    private static final Display.Frame FRAME = new Display.Frame(BlockPos.parse("world,1,65,1"), 0, 0, 7);

    @Test
    void kindsDemandWhatTheyDraw() {
        assertThrows(IllegalArgumentException.class, () -> new Display("x", Display.Kind.ROADMAP, "main", List.of(),
                null, 1, 1, List.of(FRAME), List.of(), null), "a roadmap needs a service");
        assertThrows(IllegalArgumentException.class, () -> new Display("x", Display.Kind.LOGO, null, List.of(),
                null, 1, 1, List.of(FRAME), List.of(), null), "a logo needs a station");
        assertThrows(IllegalArgumentException.class, () -> new Display("x", Display.Kind.STATUS, null, List.of(),
                null, 0, 0, List.of(), List.of(), null), "a picture needs frames or a map");
        assertThrows(IllegalArgumentException.class, () -> new Display("x", Display.Kind.HOLOGRAM, "main", List.of(),
                null, 0, 0, List.of(), List.of(), null), "a hologram needs a position");
        assertThrows(IllegalArgumentException.class, () -> new Display("x", Display.Kind.MAP, null, List.of(),
                null, 1, 1, List.of(new Display.Frame(BlockPos.parse("world,1,65,1"), 2, 0, 7)), List.of(), null),
                "frames must lie inside the mosaic");
        Display status = new Display("status", Display.Kind.STATUS, null, List.of(), null, 2, 1,
                List.of(FRAME, new Display.Frame(BlockPos.parse("world,2,65,1"), 1, 0, 8)), List.of(9), null);
        assertEquals(List.of(9, 7, 8), status.allMaps());
        assertEquals(Display.Kind.BOARD, Display.Kind.parse("Board"));
        assertTrue(Display.Kind.HOLOGRAM.live());
    }

    @Test
    void hologramPositionsRoundTrip() {
        UUID entity = UUID.randomUUID();
        Display.Hologram hologram = new Display.Hologram("world", 1.5, 66, -3.25, entity);
        assertEquals("world,1.5,66,-3.25", hologram.formatPosition());
        assertEquals(hologram, Display.Hologram.parse(hologram.formatPosition(), entity));
        assertEquals("my,world", Display.Hologram.parse("my,world,1,2,3", null).world());
    }

    @Test
    void describesItself() {
        Network network = Network.builder()
                .station(new Station("main", "Main St.", Map.of("1", Platform.of("1")), null))
                .line(Line.of("a", "Line A", 0xE53935).withService(Service.of(ServiceKey.parse("a/north"), "Local",
                        List.of(Stop.parse("main:1"), Stop.of("end")))))
                .build();
        Display roadmap = new Display("main-roadmap", Display.Kind.ROADMAP, "main", List.of("1"), ServiceKey.parse("a/north"),
                1, 1, List.of(FRAME), List.of(), null);
        assertEquals("Roadmap of a/north at Main St. platform 1 (1 x 1 frames at 1 65 1)", roadmap.describe(network));
        Display hologram = new Display("main-hologram", Display.Kind.HOLOGRAM, "main", List.of(), null, 0, 0, List.of(),
                List.of(), new Display.Hologram("world", 1, 66, 1, null));
        assertEquals("Hologram board at Main St. (world,1,66,1)", hologram.describe(network));
        assertTrue(network.issues().stream().anyMatch(issue -> issue.contains("unknown station 'end'")));
        Network withDisplay = network.toBuilder().display(new Display("x", Display.Kind.LOGO, "nowhere", List.of(), null,
                1, 1, List.of(FRAME), List.of(), null)).build();
        assertTrue(withDisplay.issues().stream().anyMatch(issue -> issue.equals("Display x is at unknown station 'nowhere'")));
    }

    @Test
    void stationsAreFoundByFootprint() {
        Station main = new Station("main", "Main St.", Map.of(), null, null, Box.parse("world,0,60,0,10,70,10"));
        Network network = Network.builder().station(main).station(Station.of("other", "Other")).build();
        assertEquals("main", network.stationAt("world", 5.5, 64, 9.9).orElseThrow().id());
        assertTrue(network.stationAt("world", 11, 64, 5).isEmpty());
        assertTrue(network.stationAt("nether", 5, 64, 5).isEmpty());
        assertEquals(main.footprint(), main.withName("Main").footprint(), "edits keep the footprint");
    }
}
