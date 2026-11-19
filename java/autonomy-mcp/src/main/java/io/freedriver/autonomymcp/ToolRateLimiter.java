package io.freedriver.autonomymcp;

import io.freedriver.ratelimit.HitWindow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;


@ApplicationScoped
public class ToolRateLimiter {

    private final int permits;
    private final long windowMs;
    private final HitWindow hits;

    @Inject
    public ToolRateLimiter(AutonomyMcpConfig config, HitWindow hits) {
        this(config.toolRateLimit().permits(), config.toolRateLimit().window().toMillis(), hits);
    }

    ToolRateLimiter(int permits, long windowMs, HitWindow hits) {
        this.permits = permits;
        this.windowMs = windowMs;
        this.hits = hits;
    }

    public boolean tryAcquire(String subject, String tool) {
        return tryAcquire(subject, tool, 1);
    }

    /**
     * Takes {@code count} hits from the caller's window for this tool, all or none. When the window
     * holds fewer than {@code count} free hits, the hits this call took are given back and the answer is false.
     */
    public boolean tryAcquire(String subject, String tool, int count) {
        String key = "tool:" + subject + ":" + tool;
        List<String> taken = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String token = hits.tryAdd(key, windowMs, permits);
            if (token == null) {
                taken.forEach(hit -> hits.remove(key, hit));
                return false;
            }
            taken.add(token);
        }
        return true;
    }
}
