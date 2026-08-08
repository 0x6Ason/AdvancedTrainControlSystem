package dev.advancedtrains.atcs.receiver;

import java.util.Objects;

public final class NoOpReceiverRegistrar implements ReceiverRegistrar {
    private final String reason;

    public NoOpReceiverRegistrar(String reason) {
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    @Override
    public boolean registered() {
        return false;
    }

    @Override
    public String statusMessage() {
        return reason;
    }

    @Override
    public void close() {
        // Nothing was registered.
    }
}

