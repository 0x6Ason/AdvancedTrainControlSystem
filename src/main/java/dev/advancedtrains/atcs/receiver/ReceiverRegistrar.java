package dev.advancedtrains.atcs.receiver;

public interface ReceiverRegistrar extends AutoCloseable {
    boolean registered();

    String statusMessage();

    @Override
    void close();
}

