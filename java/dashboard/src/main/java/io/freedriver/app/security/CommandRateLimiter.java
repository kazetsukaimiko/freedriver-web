package io.freedriver.app.security;

import io.freedriver.app.appliances.AppliancesConfig;
import io.freedriver.ratelimit.HitWindow;
import io.freedriver.ratelimit.MemoryHitWindow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.NonNull;

@ApplicationScoped
public class CommandRateLimiter {

    private final AppliancesConfig config;
    private final HitWindow hits;

    @Inject
    public CommandRateLimiter(AppliancesConfig config, HitWindow hits) {
        this.config = config;
        this.hits = hits;
    }

    CommandRateLimiter(AppliancesConfig config) {
        this(config, new MemoryHitWindow());
    }

    public boolean tryAcquire(@NonNull String user) {
        int max = Math.max(1, config.rateLimit().permits());
        long windowMs = Math.max(1, config.rateLimit().window().toMillis());
        long now = System.currentTimeMillis();
        return hits.tryAdd("command:" + user, now, windowMs, max) != null;
    }

    public void reset() {
        hits.clear();
    }
}
