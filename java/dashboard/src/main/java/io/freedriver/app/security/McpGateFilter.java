package io.freedriver.app.security;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.security.Principal;
import java.time.Instant;
import java.util.Map;

/**
 * Rate-limit and audit bearer hits on {@code /autonomy/mcp}. Missing {@code sub}
 * is refused. Permits reuse the appliances window (30/60s, 8/60s in test).
 */
@Provider
@ApplicationScoped
@Priority(Priorities.AUTHORIZATION - 10)
public class McpGateFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final String AUDITED = "io.freedriver.mcp.audited";

    private final CommandRateLimiter limiter;
    private final SecurityIdentity identity;
    private final McpAudit audit;

    @Inject
    public McpGateFilter(CommandRateLimiter limiter, SecurityIdentity identity, McpAudit audit) {
        this.limiter = limiter;
        this.identity = identity;
        this.audit = audit;
    }

    @Override
    public void filter(ContainerRequestContext request) {
        if (!McpPaths.matches(path(request))) {
            return;
        }
        if (identity == null || identity.isAnonymous()) {
            return;
        }
        String sub = OidcSubjects.subject(identity);
        if (sub == null) {
            request.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(Map.of())
                    .build());
            return;
        }
        if (!limiter.tryAcquire("mcp:" + sub)) {
            request.abortWith(Response.status(429).build());
        }
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        if (!McpPaths.matches(path(request))) {
            return;
        }
        if (Boolean.TRUE.equals(request.getProperty(AUDITED))) {
            return;
        }
        request.setProperty(AUDITED, Boolean.TRUE);
        response.getHeaders().remove("Set-Cookie");
        audit.record(new McpAudit.Event(
                Instant.now(),
                OidcSubjects.subject(identity),
                azp(identity),
                null,
                null,
                null,
                result(response.getStatus())));
    }

    static String azp(SecurityIdentity identity) {
        if (identity == null || identity.isAnonymous()) {
            return null;
        }
        Object attribute = identity.getAttribute("azp");
        if (attribute instanceof String value && !value.isBlank()) {
            return value;
        }
        Principal principal = identity.getPrincipal();
        if (principal instanceof JsonWebToken jwt) {
            Object claim = jwt.getClaim("azp");
            if (claim instanceof String value && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    static String result(int status) {
        if (status == 429) {
            return "rate-limited";
        }
        if (status >= 200 && status < 300) {
            return "allowed";
        }
        if (status >= 500) {
            return "error";
        }
        return "denied";
    }

    private static String path(ContainerRequestContext request) {
        String path = request.getUriInfo().getPath();
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }
}
