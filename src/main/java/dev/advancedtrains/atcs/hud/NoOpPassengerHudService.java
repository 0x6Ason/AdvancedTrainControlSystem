package dev.advancedtrains.atcs.hud;

import java.util.Objects;

public final class NoOpPassengerHudService implements PassengerHudService {
    private final String reason;

    public NoOpPassengerHudService(String reason) {
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    @Override
    public boolean running() {
        return false;
    }

    @Override
    public String statusMessage() {
        return reason;
    }

    @Override
    public void close() {
        // No scheduled HUD task exists.
    }
}

