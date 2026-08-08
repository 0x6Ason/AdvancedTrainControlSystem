package dev.advancedtrains.atcs.runtime;

import dev.advancedtrains.atcs.event.OperationalEvent;
import dev.advancedtrains.atcs.integration.DependencyStatus;
import dev.advancedtrains.atcs.receiver.ReceiverRegistrar;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public final class SystemRuntimeState {
    private final OperatingMode configuredMode;
    private final Path databasePath;
    private final int schemaVersion;
    private final boolean databaseHealthy;
    private final DependencyStatus trainCarts;
    private final DependencyStatus bkCommonLib;
    private final DependencyStatus signLink;
    private final ReceiverRegistrar receiverRegistrar;
    private final Instant startedAt;
    private final AtomicLong recordedEventCount;

    public SystemRuntimeState(
            OperatingMode configuredMode,
            Path databasePath,
            int schemaVersion,
            boolean databaseHealthy,
            DependencyStatus trainCarts,
            DependencyStatus bkCommonLib,
            DependencyStatus signLink,
            ReceiverRegistrar receiverRegistrar,
            Instant startedAt,
            long initialEventCount) {
        this.configuredMode = Objects.requireNonNull(configuredMode, "configuredMode");
        this.databasePath = Objects.requireNonNull(databasePath, "databasePath");
        this.schemaVersion = schemaVersion;
        this.databaseHealthy = databaseHealthy;
        this.trainCarts = Objects.requireNonNull(trainCarts, "trainCarts");
        this.bkCommonLib = Objects.requireNonNull(bkCommonLib, "bkCommonLib");
        this.signLink = Objects.requireNonNull(signLink, "signLink");
        this.receiverRegistrar = Objects.requireNonNull(receiverRegistrar, "receiverRegistrar");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.recordedEventCount = new AtomicLong(initialEventCount);
    }

    public void recordEvent(OperationalEvent ignored) {
        recordedEventCount.incrementAndGet();
    }

    public boolean controlWritesEnabled() {
        return configuredMode == OperatingMode.ACTIVE
                && databaseHealthy
                && trainCarts.present()
                && bkCommonLib.present();
    }

    public OperatingMode configuredMode() {
        return configuredMode;
    }

    public Path databasePath() {
        return databasePath;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public boolean databaseHealthy() {
        return databaseHealthy;
    }

    public DependencyStatus trainCarts() {
        return trainCarts;
    }

    public DependencyStatus bkCommonLib() {
        return bkCommonLib;
    }

    public DependencyStatus signLink() {
        return signLink;
    }

    public ReceiverRegistrar receiverRegistrar() {
        return receiverRegistrar;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public long recordedEventCount() {
        return recordedEventCount.get();
    }
}

