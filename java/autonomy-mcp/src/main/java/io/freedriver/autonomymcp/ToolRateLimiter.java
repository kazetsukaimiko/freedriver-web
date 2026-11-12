package io.freedriver.autonomymcp;

import io.freedriver.ratelimit.HitWindow;
import io.freedriver.ratelimit.MemoryHitWindow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;

@ApplicationScoped
public class ToolRateLimiter {

    private final int permits;
    private final long windowMs;
    private final HitWindow hits;

    @Inject
    public ToolRateLimiter(
            @ConfigProperty(name = "freedriver.mcp.tool-rate-limit.permits", defaultValue = "30") int permits,
            @ConfigProperty(name = "freedriver.mcp.tool-rate-limit.window", defaultValue = "60s") Duration window,
            HitWindow hits) {
        this.permits = Math.max(1, permits);
        this.windowMs = Math.max(1, window.toMillis());
        this.hits = hits;
    }

    ToolRateLimiter(int permits, Duration window) {
        this(permits, window, new MemoryHitWindow());
    }

    public boolean tryAcquire(String subject, String tool) {
        long now = System.currentTimeMillis();
        return hits.tryAdd("tool:" + subject + ":" + tool, now, windowMs, permits) != null;
    }

    public void reset() {
        hits.clear();
    }
}
