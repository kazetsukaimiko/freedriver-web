package io.freedriver.app.security;

import io.freedriver.app.appliances.AppliancesConfig;
import io.freedriver.dashboard.api.RateLimitBucket;
import io.freedriver.ratelimit.HitWindow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.NonNull;

/**
 * One window per bucket per caller. The key is {@code bucket.key() + ":" + caller}; every bucket uses the
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

    public static String key(@NonNull RateLimitBucket bucket, @NonNull String caller) {
        return bucket.key() + ":" + caller;
    }

    public boolean tryAcquire(@NonNull RateLimitBucket bucket, @NonNull String caller) {
        int max = config.rateLimit().permits();
        long windowMs = config.rateLimit().window().toMillis();
        return hits.tryAdd(key(bucket, caller), windowMs, max) != null;
    }
}
