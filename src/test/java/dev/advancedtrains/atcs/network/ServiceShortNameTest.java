package dev.advancedtrains.atcs.network;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The short name a sign line gets when the service's name does not fit. */
class ServiceShortNameTest {

    @Test
    void abbreviatesDirectionsAndLongNames() {
        assertEquals("CCW", Service.abbreviate("Counter-clockwise"));
        assertEquals("CCW", Service.abbreviate("counterclockwise"));
        assertEquals("CCW", Service.abbreviate("Anticlockwise"));
        assertEquals("CW", Service.abbreviate("Clockwise"));
        assertEquals("ETM", Service.abbreviate("Express to Main"));
        assertEquals("Local", Service.abbreviate("Local"), "a single word has no shorter form");
    }

    @Test
    void shortNameSetByHandWinsAndBlankClearsIt() {
        Service loop = Service.of(ServiceKey.parse("5/ccw"), "Counter-clockwise", List.of(Stop.of("s1"), Stop.of("s2")));
        assertNull(loop.shortName());
        assertEquals("CCW", loop.shortLabel());
        assertEquals("Anti", loop.withShortName(" Anti ").shortLabel());
        assertEquals("Anti", loop.withShortName(" Anti ").shortName(), "stored stripped");
        assertNull(loop.withShortName("Anti").withShortName("  ").shortName(), "blank clears the short name");
        assertEquals("CCW", loop.withShortName("Anti").withShortName(null).shortLabel());
    }

    @Test
    void mirrorsRepeatALineAndNeverDoubleAsOrderedSigns() {
        BlockPos first = BlockPos.parse("world,1,64,1");
        BlockPos second = BlockPos.parse("world,2,64,1");
        BlockPos entrance = BlockPos.parse("world,9,64,9");
        Board board = new Board("b", "s1", List.of(), List.of(first)).withSign(second).withMirror(entrance, 2);
        assertEquals(List.of(first, second), board.signs());
        assertEquals(2, board.mirrors().get(entrance));
        assertEquals(List.of(first, second, entrance), board.allSigns());
        assertEquals(2, board.lines());
        assertEquals(3, board.withMirror(BlockPos.parse("world,9,64,8"), 3).lines(), "a mirror of a later line asks for that many departures");
        Board promoted = board.withSign(entrance);
        assertEquals(List.of(first, second, entrance), promoted.signs());
        assertEquals(0, promoted.mirrors().size(), "a mirror made an ordered sign is no mirror any more");
        Board demoted = board.withMirror(second, 1);
        assertEquals(List.of(first), demoted.signs());
        assertEquals(1, demoted.mirrors().get(second));
        assertEquals(List.of(first), board.withoutSign(second).withoutSign(entrance).allSigns());
    }
}
