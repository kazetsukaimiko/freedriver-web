package io.freedriver.app.security;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;

/**
 * App-wide MCP path rules: bearer only on {@code /autonomy/mcp}, that path
 * never takes a portal session cookie, other portal routes refuse a bearer
 * while OIDC is on, and a bearer response never sets a cookie.
 */
@ApplicationScoped
public class McpBearerFilter {

    private final boolean oidcEnabled;
    private final McpAudit audit;

    @Inject
    public McpBearerFilter(
            @ConfigProperty(name = "quarkus.oidc.enabled", defaultValue = "false") boolean oidcEnabled,
            McpAudit audit) {
        this.oidcEnabled = oidcEnabled;
        this.audit = audit;
    }

    void register(@Observes Filters filters) {
        filters.register(this::filter, 160);
    }

    void filter(RoutingContext rc) {
        String path = rc.request().path();
        if (McpPaths.matches(path)) {
            if (McpPaths.hasSessionCookie(rc.request().getHeader("Cookie"))) {
                deny(rc);
                return;
            }
            if (oidcEnabled && !McpPaths.hasBearer(rc.request().getHeader("Authorization"))) {
                deny(rc);
                return;
            }
            rc.addHeadersEndHandler(ignored -> rc.response().headers().remove("Set-Cookie"));
            rc.next();
            return;
        }
        if (oidcEnabled && McpPaths.hasBearer(rc.request().getHeader("Authorization"))) {
            deny(rc);
            return;
        }
        rc.next();
    }

    private void deny(RoutingContext rc) {
        audit.record(new McpAudit.Event(Instant.now(), null, null, null, null, null, "denied"));
        rc.response()
                .setStatusCode(401)
                .putHeader("Content-Type", "application/json")
                .end("{}");
    }
}
