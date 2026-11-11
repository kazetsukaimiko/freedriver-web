package io.freedriver.autonomymcp;

import io.freedriver.ratelimit.HitWindow;
import io.freedriver.ratelimit.MemoryHitWindow;
import io.freedriver.ratelimit.RedisHitWindow;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Production tool windows are Redis. Dev and test keep a process-local window. */
@ApplicationScoped
public class RateLimitWindows {

    @Produces
    @IfBuildProfile("prod")
    @ApplicationScoped
    public HitWindow redis(
            @ConfigProperty(name = "freedriver.redis.url", defaultValue = "redis://redis:6379") String url) {
        return new RedisHitWindow(url, "freedriver:rate-keys:mcp");
    }

    @Produces
    @IfBuildProfile(anyOf = {"dev", "test"})
    @ApplicationScoped
    public HitWindow memory() {
        return new MemoryHitWindow();
    }
}
