package dev.advancedtrains.atcs.map;

import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.operation.TrackPaths;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class MapGeometryTest {
    private static final List<TrackPaths.Point> EASTWARD = List.of(
            new TrackPaths.Point(0, 64, 0), new TrackPaths.Point(50, 64, 0), new TrackPaths.Point(100, 64, 0));

    @Test
    void offsetsAPolylineSideways() {
        List<TrackPaths.Point> moved = MapGeometry.offset(EASTWARD, 2);
        assertEquals(3, moved.size());
        assertEquals(0, moved.get(0).x(), 0.001);
        assertEquals(2, moved.get(0).z(), 0.001, "a line heading east is pushed south by +2");
        assertEquals(2, moved.get(1).z(), 0.001);
        assertEquals(64, moved.get(1).y(), 0.001, "height is untouched");
        assertSame(EASTWARD, MapGeometry.offset(EASTWARD, 0), "no offset returns the same list");
        List<TrackPaths.Point> back = MapGeometry.offset(List.of(EASTWARD.get(2), EASTWARD.get(1), EASTWARD.get(0)), 2);
        assertEquals(-2, back.get(0).z(), 0.001, "the same track run the other way is pushed the other way");
    }

    @Test
    void linesOnTheSameStretchAreSpreadApart() {
        Line a = Line.of("a", "Line A", 0xE53935);
        Line b = Line.of("b", "Line B", 0x1E88E5);
        MapGeometry.Segment first = new MapGeometry.Segment("a1", a, "world", EASTWARD, true);
        MapGeometry.Segment second = new MapGeometry.Segment("b1", b, "world", EASTWARD, true);
        MapGeometry.Segment elsewhere = new MapGeometry.Segment("a2", a, "world",
                List.of(new TrackPaths.Point(0, 64, 500), new TrackPaths.Point(100, 64, 500)), true);
        List<MapGeometry.Segment> spread = MapGeometry.spread(List.of(first, second, elsewhere));
        assertEquals(-MapGeometry.SPREAD / 2, spread.get(0).points().get(1).z(), 0.001, "line a to one side");
        assertEquals(MapGeometry.SPREAD / 2, spread.get(1).points().get(1).z(), 0.001, "line b to the other");
        assertEquals(500, spread.get(2).points().get(1).z(), 0.001, "a stretch run by one line stays put");
        List<MapGeometry.Segment> sameLine = MapGeometry.spread(List.of(first, new MapGeometry.Segment("a3", a, "world", EASTWARD, true)));
        assertEquals(0, sameLine.get(0).points().get(1).z(), 0.001, "two services of one line are not spread");
    }
}
