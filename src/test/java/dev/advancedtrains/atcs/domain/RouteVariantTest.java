package dev.advancedtrains.atcs.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RouteVariantTest {
    @Test
    void circularRouteRequiresAnchorAndLoopPolicy() {
        assertThrows(IllegalArgumentException.class, () -> route(true, Optional.empty(), TerminalPolicy.LOOP_CONTINUE));
        assertThrows(IllegalArgumentException.class,
                () -> route(true, Optional.of(DomainId.of("CEN")), TerminalPolicy.TURNBACK));
        assertDoesNotThrow(
                () -> route(true, Optional.of(DomainId.of("CEN")), TerminalPolicy.LOOP_CONTINUE));
    }

    @Test
    void linearRouteCannotUseLoopContinue() {
        assertThrows(IllegalArgumentException.class,
                () -> route(false, Optional.empty(), TerminalPolicy.LOOP_CONTINUE));
        assertDoesNotThrow(() -> route(false, Optional.empty(), TerminalPolicy.TURNBACK));
    }

    private RouteVariant route(boolean circular, Optional<DomainId> anchor, TerminalPolicy terminalPolicy) {
        return new RouteVariant(
                DomainId.of("CENTRAL_CW"),
                DomainId.of("CENTRAL"),
                "Central clockwise",
                "Clockwise",
                Optional.of("Central Station"),
                circular,
                anchor,
                terminalPolicy,
                List.of(DomainId.of("SEGMENT_1"), DomainId.of("SEGMENT_2")));
    }
}

