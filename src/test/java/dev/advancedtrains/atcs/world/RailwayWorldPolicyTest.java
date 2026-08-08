package dev.advancedtrains.atcs.world;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RailwayWorldPolicyTest {
    @Test
    void fixedPolicyMatchesWorldNamesWithoutCaseSensitivity() {
        RailwayWorldPolicy policy = RailwayWorldPolicy.fixed(Set.of("Railway", "Events"));

        assertTrue(policy.isActive("railway"));
        assertTrue(policy.isActive("EVENTS"));
        assertFalse(policy.isActive("world"));
    }

    @Test
    void unrestrictedPolicySupportsRepositoryTestsWithoutServerState() {
        RailwayWorldPolicy policy = RailwayWorldPolicy.unrestricted();

        assertTrue(policy.isActive("world"));
        assertFalse(policy.isActive("   "));
    }
}
