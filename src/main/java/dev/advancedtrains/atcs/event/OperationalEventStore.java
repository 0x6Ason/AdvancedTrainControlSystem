package dev.advancedtrains.atcs.event;

import java.sql.SQLException;

public interface OperationalEventStore {
    /**
     * Appends the event if its idempotency key has not already been recorded.
     *
     * @return true when the event was inserted, or false when it was a duplicate
     */
    boolean appendIfAbsent(OperationalEvent event) throws SQLException;
}

