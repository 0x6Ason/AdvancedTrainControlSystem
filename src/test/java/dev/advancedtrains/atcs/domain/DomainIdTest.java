package dev.advancedtrains.atcs.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DomainIdTest {
    @Test
    void normalizesHumanInput() {
        assertEquals("CENTRAL_P1", DomainId.of(" central_p1 ").value());
    }

    @Test
    void rejectsIdentifiersThatAreUnsafeForConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> DomainId.of("Central:P1"));
        assertThrows(IllegalArgumentException.class, () -> DomainId.of(""));
        assertThrows(IllegalArgumentException.class, () -> DomainId.of("station name"));
    }
}

