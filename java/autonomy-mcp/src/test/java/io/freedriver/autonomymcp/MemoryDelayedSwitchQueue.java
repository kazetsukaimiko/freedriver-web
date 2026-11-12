package io.freedriver.autonomymcp;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

/** Delayed switches for the test profile. One process-local stand-in for Redis. */
@Alternative
@Priority(1)
@ApplicationScoped
public class MemoryDelayedSwitchQueue implements DelayedSwitchQueue {

    private final SwitchDelayRecords records = new SwitchDelayRecords(new MemorySwitchKeys());

    @Override
    public void schedule(String id, long dueEpochMillis, String payload, long ttlSeconds) {
        records.schedule(id, dueEpochMillis, payload, ttlSeconds);
    }

    @Override
    public String lockDue(long nowEpochMillis) {
        return records.lockDue(nowEpochMillis);
    }

    @Override
    public String payload(String id) {
        return records.payload(id);
    }

    @Override
    public void remove(String id) {
        records.remove(id);
    }
}
