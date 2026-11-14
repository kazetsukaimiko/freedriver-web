package io.freedriver.app.security;

import io.freedriver.app.appliances.AppliancesConfig;
import io.freedriver.ratelimit.HitWindow;
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

    public boolean tryAcquire(@NonNull String user) {
        int max = Math.max(1, config.rateLimit().permits());
        long windowMs = Math.max(1, config.rateLimit().window().toMillis());
        return hits.tryAdd("command:" + user, windowMs, max) != null;
    }
}
