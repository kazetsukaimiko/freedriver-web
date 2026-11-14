package io.freedriver.ratelimit;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Process-local window for direct tests. The clock stands in for the Redis server's {@code TIME}. */
public final class MemoryHitWindow implements HitWindow {

    private final Clock clock;
    private final Map<String, Deque<Hit>> hits = new HashMap<>();

    public MemoryHitWindow() {
        this(Clock.systemUTC());
    }

    public MemoryHitWindow(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized String tryAdd(String key, long windowMs, int max) {
        Deque<Hit> deque = prune(key, windowMs);
        if (deque != null && deque.size() >= max) {
            return null;
        }
        return append(key);
    }

    @Override
    public synchronized String add(String key, long windowMs) {
        prune(key, windowMs);
        return append(key);
    }

    @Override
    public synchronized int count(String key, long windowMs) {
        Deque<Hit> deque = prune(key, windowMs);
        return deque == null ? 0 : deque.size();
    }

    @Override
    public synchronized void remove(String key, String token) {
        Deque<Hit> deque = hits.get(key);
        if (deque == null) {
            return;
        }
        deque.removeIf(hit -> hit.token().equals(token));
        if (deque.isEmpty()) {
            hits.remove(key);
        }
    }

    @Override
    public synchronized void delete(String key) {
        hits.remove(key);
    }

    private String append(String key) {
        String token = UUID.randomUUID().toString();
        hits.computeIfAbsent(key, ignored -> new ArrayDeque<>()).addLast(new Hit(clock.millis(), token));
        return token;
    }

    private Deque<Hit> prune(String key, long windowMs) {
        Deque<Hit> deque = hits.get(key);
        if (deque == null) {
            return null;
        }
        long now = clock.millis();
        while (!deque.isEmpty() && now - deque.peekFirst().at() >= windowMs) {
            deque.pollFirst();
        }
        if (deque.isEmpty()) {
            hits.remove(key);
            return null;
        }
        return deque;
    }

    private record Hit(long at, String token) {
    }
}
