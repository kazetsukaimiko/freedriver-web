package io.freedriver.app.security;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import lombok.NonNull;

import java.security.Principal;

/**
 * Spends one permit from the caller's window in one bucket. {@link RateLimitedFeature} builds one
 * instance per bucket and registers it on the routes that carry that bucket.
 */
public class RateLimitedFilter implements ContainerRequestFilter {

    private final String bucket;
    private final CallerRateLimiter limiter;
    private final SecurityIdentity identity;

    public RateLimitedFilter(@NonNull String bucket, @NonNull CallerRateLimiter limiter, @NonNull SecurityIdentity identity) {
        this.bucket = bucket;
        this.limiter = limiter;
        this.identity = identity;
    }

    String bucket() {
        return bucket;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String caller = callerKey(identity);
        if (caller == null) {
            throw new RateLimitedException();
        }
        if (!limiter.tryAcquire(bucket, caller)) {
            throw new RateLimitedException();
        }
    }

    static String callerKey(SecurityIdentity identity) {
        if (identity == null || identity.isAnonymous()) {
            return null;
        }
        String sub = OidcSubjects.subject(identity);
        if (sub != null) {
            return sub;
        }
        Principal principal = identity.getPrincipal();
        if (principal == null) {
            return null;
        }
        String name = principal.getName();
        if (name == null || name.isBlank()) {
            return null;
        }
        return name;
    }
}
