package dev.advancedtrains.atcs.control;

import dev.advancedtrains.atcs.network.PlatformRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StationSignConversionTest {
    private static final PlatformRef PLATFORM = new PlatformRef("firework", "2");

    private static StationSignConversion convert(String... lines) {
        return StationSignConversion.convert(List.of(lines), PLATFORM).orElseThrow();
    }

    @Test
    void carriesDelayAndDirectionOver() {
        StationSignConversion plain = convert("[+train]", "station", "00:02", "continue");
        assertEquals(List.of("[+train]", "atcs station", "firework:2", "2s"), plain.lines());
        assertTrue(plain.notes().isEmpty(), plain.notes()::toString);
        assertEquals(List.of("[+train]", "atcs station", "firework:2", "5s reverse"),
                convert("[+train]", "station", "5", "reverse").lines());
        assertEquals("3723s left", convert("[+train]", "Station", "1:02:03", "LEFT").lines().get(3));
        assertEquals("90s route", convert("[+train]", "station", "1.5m", "route").lines().get(3));
    }

    @Test
    void carriesOffsetLaunchAndSpeedOver() {
        StationSignConversion full = convert("[+train]", "station 30 2.5m", "5s", "reverse 0.4");
        assertEquals("5s reverse 2.5m 30b 0.4v", full.lines().get(3));
        StopOptions options = StopOptions.parse(full.lines().get(3));
        assertEquals(5, options.dwellSeconds());
        assertEquals(Heading.REVERSE, options.heading());
        assertEquals(2.5, options.offset());
        assertEquals(30, options.launchDistance());
        assertEquals(0.4, options.launchSpeed());
    }

    @Test
    void notesWhatChangesAndStripsColours() {
        StationSignConversion held = convert("[+train]", "station", "", "");
        assertEquals("", held.lines().get(3));
        assertEquals(1, held.notes().size(), held.notes()::toString);
        StationSignConversion immediate = convert("§a[+train]", "station", "", "continue");
        assertEquals(List.of("[+train]", "atcs station", "firework:2", "0s"), immediate.lines());
        StationSignConversion powered = convert("[train]", "station", "3", "left");
        assertEquals("[train]", powered.lines().get(0));
        assertEquals(1, powered.notes().size());
        StationSignConversion odd = convert("[+train]", "station", "soon", "sideways");
        assertEquals(2, odd.notes().size(), odd.notes()::toString);
    }

    @Test
    void leavesOtherSignsAlone() {
        assertEquals(Optional.empty(), StationSignConversion.convert(List.of("[+train]", "announce", "Arrived: Firework", ""), PLATFORM));
        assertEquals(Optional.empty(), StationSignConversion.convert(List.of("[+train]", "atcs stop", "firework", "2"), PLATFORM));
        assertEquals(Optional.empty(), StationSignConversion.convert(List.of("Welcome", "station", "", ""), PLATFORM));
        assertTrue(StationSignConversion.isAtcsSign(List.of("[+train]", "atcs station", "firework:2", "8s")));
        assertFalse(StationSignConversion.isAtcsSign(List.of("[+train]", "station", "", "")));
    }
}
