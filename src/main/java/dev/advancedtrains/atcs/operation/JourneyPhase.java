package dev.advancedtrains.atcs.operation;

/** Where a train is relative to the stops of its service. */
public enum JourneyPhase {
    /** No platform observed since the train became known or changed service. */
    IDLE,
    /** Standing at (or entering) a calling stop. */
    AT_STOP,
    /** Running through a station without calling. */
    PASSING,
    /** Between stops. */
    IN_TRANSIT,
    /** Left the last stop of its service; no longer in passenger service. */
    COMPLETED
}
