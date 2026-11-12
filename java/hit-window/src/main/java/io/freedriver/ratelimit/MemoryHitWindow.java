package io.freedriver.ratelimit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Process-local window for the test profile, dev, and direct tests. */
public final class MemoryHitWindow implements HitWindow {

    private final Map<String, Deque<String>> hits = new HashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public synchronized String tryAdd(String key, long nowMs, long windowMs, int max) {
        Deque<String> deque = prune(key, nowMs, windowMs);
        int size = deque == null ? 0 : deque.size();
        if (size >= max) {
            return null;
        }
        return append(key, deque, nowMs);
    }

    @Override
    public synchronized String add(String key, long nowMs, long windowMs) {
        Deque<String> deque = prune(key, nowMs, windowMs);
        return append(key, deque, nowMs);
    }

    @Override
    public synchronized int count(String key, long nowMs, long windowMs) {
        Deque<String> deque = prune(key, nowMs, windowMs);
        return deque == null ? 0 : deque.size();
    }

    @Override
    public synchronized void remove(String key, String token) {
        Deque<String> deque = hits.get(key);
        if (deque == null) {
            return;
        }
        deque.remove(token);
        if (deque.isEmpty()) {
            hits.remove(key);
        }
    }

    @Override
    public synchronized void delete(String key) {
        hits.remove(key);
    }

    @Override
    public synchronized void clear() {
        hits.clear();
    }

    @Override
    public synchronized int size(String prefix, long nowMs, long windowMs) {
        int live = 0;
        for (String key : new ArrayList<>(hits.keySet())) {
            if (key.startsWith(prefix) && prune(key, nowMs, windowMs) != null) {
                live++;
            }
        }
        return live;
    }

    private String append(String key, Deque<String> deque, long nowMs) {
        if (deque == null) {
            deque = new ArrayDeque<>();
            hits.put(key, deque);
        }
        String token = nowMs + ":" + sequence.incrementAndGet();
        deque.addLast(token);
        return token;
    }

    private Deque<String> prune(String key, long nowMs, long windowMs) {
        Deque<String> deque = hits.get(key);
        if (deque == null) {
            return null;
        }
        while (!deque.isEmpty() && nowMs - stamp(deque.peekFirst()) >= windowMs) {
            deque.pollFirst();
        }
        if (deque.isEmpty()) {
            hits.remove(key);
            return null;
        }
        return deque;
    }

    private static long stamp(String token) {
        return Long.parseLong(token.substring(0, token.indexOf(':')));
    }
}
