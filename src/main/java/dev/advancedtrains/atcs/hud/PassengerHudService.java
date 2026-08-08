package dev.advancedtrains.atcs.hud;

public interface PassengerHudService extends AutoCloseable {
    boolean running();

    String statusMessage();

    @Override
    void close();
}

