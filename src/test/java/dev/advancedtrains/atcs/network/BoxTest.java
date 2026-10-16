package dev.advancedtrains.atcs.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoxTest {

    @Test
    void cornersInAnyOrderMakeTheSameBox() {
        Box box = Box.of(BlockPos.parse("world,10,70,-5"), BlockPos.parse("world,-2,64,3"));
        assertEquals(new Box("world", -2, 64, -5, 10, 70, 3), box);
        assertEquals(13, box.width());
        assertEquals(7, box.height());
        assertEquals(9, box.depth());
        assertEquals(13L * 7 * 9, box.volume());
        assertEquals(4.5, box.centreX(), 0.001);
    }

    @Test
    void containsWholeBlocksInclusive() {
        Box box = Box.parse("world,0,64,0,3,64,3");
        assertTrue(box.contains("world", 3.9, 64.5, 0.0), "the far corner block counts up to its edge");
        assertFalse(box.contains("world", 4.0, 64.5, 0.0));
        assertFalse(box.contains("world", 1, 65.0, 1), "one block tall: y 65 is outside");
        assertFalse(box.contains("nether", 1, 64, 1));
        assertTrue(box.contains(new BlockPos("world", 3, 64, 3)));
    }

    @Test
    void formatsAndParsesWithCommasInWorldNames() {
        Box box = Box.parse("my,world,1,2,3,4,5,6");
        assertEquals("my,world", box.world());
        assertEquals("my,world,1,2,3,4,5,6", box.format());
        assertEquals(box, Box.parse(box.format()));
        assertThrows(IllegalArgumentException.class, () -> Box.parse("world,1,2,3"));
        assertThrows(IllegalArgumentException.class, () -> Box.parse("world,1,2,3,4,5,x"));
        assertThrows(IllegalArgumentException.class, () -> Box.of(BlockPos.parse("a,1,1,1"), BlockPos.parse("b,2,2,2")));
    }
}
