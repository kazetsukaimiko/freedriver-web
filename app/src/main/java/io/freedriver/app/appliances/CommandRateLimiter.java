package io.freedriver.app.appliances;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class CommandRateLimiter {

    private final AppliancesConfig config;
    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    @Inject
    public CommandRateLimiter(AppliancesConfig config) {
        this.config = config;
    }

    public boolean tryAcquire(String user) {
        String key = user == null || user.isBlank() ? "anonymous" : user;
        int max = Math.max(1, config.rateLimit().permits());
        long windowMs = Math.max(1, config.rateLimit().window().toMillis());
        long now = System.currentTimeMillis();
        Deque<Long> queue = hits.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (queue) {
            while (!queue.isEmpty() && now - queue.peekFirst() >= windowMs) {
                queue.pollFirst();
            }
            if (queue.size() >= max) {
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
