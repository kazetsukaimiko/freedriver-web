package io.freedriver.autonomymcp;

import io.freedriver.ratelimit.HitWindow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;


@ApplicationScoped
public class ToolRateLimiter {

    private final int permits;
    private final long windowMs;
    private final HitWindow hits;

    @Inject
    public ToolRateLimiter(AutonomyMcpConfig config, HitWindow hits) {
        this.permits = config.toolRateLimit().permits();
        this.windowMs = config.toolRateLimit().window().toMillis();
        this.hits = hits;
    }

    public boolean tryAcquire(String subject, String tool) {
        return hits.tryAdd("tool:" + subject + ":" + tool, windowMs, permits) != null;
    }
}
