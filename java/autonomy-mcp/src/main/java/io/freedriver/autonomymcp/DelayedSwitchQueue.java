package io.freedriver.autonomymcp;

/**
 * A switch that runs at a later time. The row outlives the process that stored it.
 */
interface DelayedSwitchQueue {

    void schedule(String id, long dueEpochMillis, String payload, long ttlSeconds);

    /** Locks one row that is due and returns its id, or null when none is free. */
    String lockDue(long nowEpochMillis);

    /** The stored payload, or null when the id is gone. */
    String payload(String id);

    void remove(String id);
}
