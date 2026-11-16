package io.freedriver.app.security;

import io.freedriver.app.appliances.AppliancesConfig;
import io.freedriver.ratelimit.HitWindow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.NonNull;

/**
 * One window per bucket per caller. The key is {@code <bucket>:<caller>}; every bucket uses the
 * {@code freedriver.appliances.rate-limit} allowance.
 */
@ApplicationScoped
public class CallerRateLimiter {

    private final AppliancesConfig config;
    private final HitWindow hits;

    @Inject
    public CallerRateLimiter(AppliancesConfig config, HitWindow hits) {
        this.config = config;
        this.hits = hits;
    }

    public static String key(@NonNull String bucket, @NonNull String caller) {
        return bucket + ":" + caller;
    }

    public boolean tryAcquire(@NonNull String bucket, @NonNull String caller) {
        int max = config.rateLimit().permits();
        long windowMs = config.rateLimit().window().toMillis();
        return hits.tryAdd(key(bucket, caller), windowMs, max) != null;
    }
}
