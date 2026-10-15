package dev.advancedtrains.atcs.network;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StopEditsTest {
    private static List<Stop> stops(String... texts) {
        return List.of(texts).stream().map(Stop::parse).toList();
    }

    @Test
    void insertsAtAnchors() {
        List<Stop> line = stops("a:1", "b", "~c", "d:2");
        assertEquals(stops("x", "a:1", "b", "~c", "d:2"), StopEdits.insert(line, Stop.parse("x"), StopEdits.Anchor.FIRST, null));
        assertEquals(stops("a:1", "b", "~c", "d:2", "x"), StopEdits.insert(line, Stop.parse("x"), StopEdits.Anchor.LAST, null));
        assertEquals(stops("a:1", "x:3", "b", "~c", "d:2"), StopEdits.insert(line, Stop.parse("x:3"), StopEdits.Anchor.BEFORE, "b"));
        assertEquals(stops("a:1", "b", "~x", "~c", "d:2"), StopEdits.insert(line, Stop.parse("~x"), StopEdits.Anchor.AFTER, "b"));
        assertThrows(IllegalArgumentException.class, () -> StopEdits.insert(line, Stop.parse("x"), StopEdits.Anchor.AFTER, "nowhere"));
    }

    @Test
    void insertsBetweenConsecutiveStopsInEitherOrder() {
        List<Stop> north = stops("a", "b", "c");
        List<Stop> south = stops("c", "b", "a");
        assertEquals(stops("a", "x", "b", "c"), StopEdits.insertBetween(north, Stop.parse("x"), "a", "b", false));
        assertEquals(stops("c", "b", "x", "a"), StopEdits.insertBetween(south, Stop.parse("x"), "a", "b", false));
        assertThrows(IllegalArgumentException.class, () -> StopEdits.insertBetween(north, Stop.parse("x"), "a", "c", false));
        assertEquals(stops("a", "b", "c", "x"), StopEdits.insertBetween(north, Stop.parse("x"), "c", "a", true), "a loop closes c to a");
        assertTrue(StopEdits.adjacent(north, "b", "a", false));
        assertFalse(StopEdits.adjacent(north, "c", "a", false));
    }

    @Test
    void removesEveryStopAtAStation() {
        assertEquals(stops("a", "c"), StopEdits.remove(stops("a", "b:1", "c", "b:2"), "b"));
        assertThrows(IllegalArgumentException.class, () -> StopEdits.remove(stops("a", "c"), "b"));
    }

    @Test
    void matchesStationsAcrossAnEdit() {
        assertArrayEquals(new int[] {0, -1, 1, 2}, StopEdits.matchIndices(stops("a", "b", "c"), stops("a", "x", "b", "c")));
        assertArrayEquals(new int[] {0, 2}, StopEdits.matchIndices(stops("a", "b", "c"), stops("a", "c")));
        assertArrayEquals(new int[] {0, 1, 2, 3}, StopEdits.matchIndices(stops("a", "b", "a", "b"), stops("a", "b", "a", "b")), "loops match in order");
        assertArrayEquals(new int[] {-1, -1}, StopEdits.matchIndices(stops("a", "b"), stops("c", "d")));
    }
}
