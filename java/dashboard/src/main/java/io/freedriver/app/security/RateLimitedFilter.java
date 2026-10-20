package io.freedriver.app.security;

import io.freedriver.app.appliances.CommandRateLimiter;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import org.eclipse.microprofile.jwt.JsonWebToken;

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
        String sub = oidcSubject(identity);
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

    private static String oidcSubject(SecurityIdentity identity) {
        Object attribute = identity.getAttribute("sub");
        if (attribute instanceof String value && !value.isBlank()) {
            return value;
        }
        Principal principal = identity.getPrincipal();
        if (principal instanceof JsonWebToken jwt) {
            String subject = jwt.getSubject();
            if (subject != null && !subject.isBlank()) {
                return subject;
            }
        }
        return null;
    }
}
