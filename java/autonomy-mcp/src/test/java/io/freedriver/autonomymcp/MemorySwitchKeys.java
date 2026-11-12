package io.freedriver.autonomymcp;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory stand-in for one Redis. Two records share it. */
final class MemorySwitchKeys implements SwitchDelayRecords.Keys {

    private final ConcurrentHashMap<String, Row> rows = new ConcurrentHashMap<>();

    @Override
    public synchronized void schedule(String id, long dueEpochMillis, String payload, long ttlSeconds) {
        rows.put(id, new Row(dueEpochMillis, payload, ttlSeconds));
    }

    @Override
    public synchronized List<String> dueIds(long nowEpochMillis, int limit) {
        return rows.entrySet().stream()
                .filter(entry -> entry.getValue().due <= nowEpochMillis)
                .sorted(Comparator.comparingLong(entry -> entry.getValue().due))
                .limit(limit)
                .map(java.util.Map.Entry::getKey)
                .toList();
    }

    @Override
    public synchronized boolean tryLock(String id, long nowEpochMillis, long lockSeconds) {
        Row row = rows.get(id);
        if (row == null || row.lockUntil > nowEpochMillis) {
            return false;
        }
        row.lockUntil = nowEpochMillis + lockSeconds * 1000L;
        return true;
    }

    @Override
    public synchronized String payload(String id) {
        Row row = rows.get(id);
        return row == null ? null : row.payload;
    }

    @Override
    public synchronized void remove(String id) {
        rows.remove(id);
    }

    synchronized long ttl(String id) {
        Row row = rows.get(id);
        return row == null ? -1 : row.ttlSeconds;
    }

    private static final class Row {
        private final long due;
        private final String payload;
        private final long ttlSeconds;
        private long lockUntil;

        private Row(long due, String payload, long ttlSeconds) {
            this.due = due;
            this.payload = payload;
            this.ttlSeconds = ttlSeconds;
        }
    }
}
