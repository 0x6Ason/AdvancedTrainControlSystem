package dev.advancedtrains.atcs.command;

/** A command failed with a message for the sender. */
public final class CommandError extends Exception {
    public CommandError(String message) {
        super(message);
    }
}
