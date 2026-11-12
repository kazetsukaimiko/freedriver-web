package io.freedriver.autonomymcp;

import java.util.List;

/**
 * Claim rules for a delayed switch. Redis and the in-memory stand-in share them.
 * A lock keeps two processes from posting the same row, and it expires so a
 * dead process does not take the row with it.
 */
class SwitchDelayRecords implements DelayedSwitchQueue {

    private static final int DUE_BATCH = 8;

    interface Keys {
        void schedule(String id, long dueEpochMillis, String payload, long ttlSeconds);

        /** Due ids, earliest first, at most {@code limit}. */
        List<String> dueIds(long nowEpochMillis, int limit);

        /**
         * Holds the id until {@code nowEpochMillis} plus the lock, when the stand-in
         * keeps its own clock. Redis applies the lock from its own clock.
         */
        boolean tryLock(String id, long nowEpochMillis, long lockSeconds);

        String payload(String id);

        void remove(String id);
    }

    private final Keys keys;

    SwitchDelayRecords(Keys keys) {
        this.keys = keys;
    }

    @Override
    public void schedule(String id, long dueEpochMillis, String payload, long ttlSeconds) {
        keys.schedule(id, dueEpochMillis, payload, ttlSeconds);
    }

    @Override
    public String lockDue(long nowEpochMillis) {
        for (String id : keys.dueIds(nowEpochMillis, DUE_BATCH)) {
            if (keys.tryLock(id, nowEpochMillis, SwitchDelay.LOCK_SECONDS)) {
                return id;
            }
        }
        return null;
    }

    @Override
    public String payload(String id) {
        return keys.payload(id);
    }

    @Override
    public void remove(String id) {
        keys.remove(id);
    }
}
