package io.freedriver.app.security;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The portal takes a session cookie, not a bearer: while OIDC is on, a request with a Bearer
 * token is 401 on every dashboard route except {@code /api/mcp} and {@code /api/appliances}.
 * There the {@link InternalTenant} checks it: autonomy-mcp's exchanged token for the connecting
 * user. The MCP bearer endpoint is the autonomy-mcp process.
 */
@ApplicationScoped
public class McpBearerFilter {

    private final boolean oidcEnabled;

    @Inject
    public McpBearerFilter(@ConfigProperty(name = "quarkus.oidc.enabled", defaultValue = "false") boolean oidcEnabled) {
        this.oidcEnabled = oidcEnabled;
    }

    void register(@Observes Filters filters) {
        filters.register(this::filter, 160);
    }

    void filter(RoutingContext rc) {
        if (oidcEnabled && BearerHeaders.hasBearer(rc.request().getHeader("Authorization"))
                && !InternalTenant.internalPath(rc.normalizedPath())) {
            rc.response()
                    .setStatusCode(401)
                    .putHeader("Content-Type", "application/json")
                    .end("{}");
            return;
        }
        rc.next();
    }
}
