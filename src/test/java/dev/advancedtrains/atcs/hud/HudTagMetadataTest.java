package dev.advancedtrains.atcs.hud;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HudTagMetadataTest {
    @Test
    void readsTemporaryPassengerInformationOverrides() {
        HudTagMetadata metadata = HudTagMetadata.parse(List.of(
                "atcs:line=Central_Line",
                "atcs:service=Limited_Express",
                "atcs:destination=Airport_Terminal",
                "atcs:next=Harbour"));

        assertEquals("Central Line", metadata.line().orElseThrow());
        assertEquals("Limited Express", metadata.serviceType().orElseThrow());
        assertEquals("Airport Terminal", metadata.destination().orElseThrow());
        assertEquals("Harbour", metadata.nextStation().orElseThrow());
    }
}

