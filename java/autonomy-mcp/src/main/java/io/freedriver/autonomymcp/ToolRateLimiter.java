package io.freedriver.autonomymcp;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ToolRateLimiter {

    private final int permits;
    private final long windowMs;
    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public ToolRateLimiter(
            @ConfigProperty(name = "freedriver.mcp.tool-rate-limit.permits", defaultValue = "30") int permits,
            @ConfigProperty(name = "freedriver.mcp.tool-rate-limit.window", defaultValue = "60s") Duration window) {
        this.permits = Math.max(1, permits);
        this.windowMs = Math.max(1, window.toMillis());
    }

    public boolean tryAcquire(String subject, String tool) {
        String key = subject + ":" + tool;
        long now = System.currentTimeMillis();
        Deque<Long> queue = hits.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (queue) {
            while (!queue.isEmpty() && now - queue.peekFirst() >= windowMs) {
                queue.pollFirst();
            }
            if (queue.size() >= permits) {
                return false;
            }
            queue.addLast(now);
            return true;
        }
    }

    public void reset() {
        hits.clear();
    }
}
