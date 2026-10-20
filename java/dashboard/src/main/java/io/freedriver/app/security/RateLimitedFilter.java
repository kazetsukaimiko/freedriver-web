package io.freedriver.app.security;

import io.freedriver.app.appliances.CommandRateLimiter;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;

import java.security.Principal;

@ApplicationScoped
@Priority(Priorities.AUTHORIZATION + 100)
public class RateLimitedFilter implements ContainerRequestFilter {

    private final CommandRateLimiter limiter;
    private final SecurityIdentity identity;

    @Inject
    public RateLimitedFilter(CommandRateLimiter limiter, SecurityIdentity identity) {
        this.limiter = limiter;
        this.identity = identity;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String key = callerKey(identity);
        if (key == null) {
            throw new RateLimitedException();
        }
        if (!limiter.tryAcquire(key)) {
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
