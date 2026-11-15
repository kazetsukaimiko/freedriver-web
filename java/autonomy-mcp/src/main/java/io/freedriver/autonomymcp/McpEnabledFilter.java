package io.freedriver.autonomymcp;

import io.freedriver.mcp.McpEndpoint;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Off answers 404 on {@code /autonomy/mcp} before HTTP auth, so anonymous
 * callers do not see a login challenge while the process is parked.
 */
@ApplicationScoped
public class McpEnabledFilter {

    private final boolean enabled;
    private final McpEndpoint endpoint;

    @Inject
    public McpEnabledFilter(AutonomyMcpConfig config, McpEndpoint endpoint) {
        this.enabled = config.enabled();
        this.endpoint = endpoint;
    }

    void register(@Observes Filters filters) {
        filters.register(this::filter, 10_000);
    }

    void filter(RoutingContext context) {
        if (enabled) {
            context.next();
            return;
        }
        if (endpoint.isMcpPath(context.request().path())) {
            context.response().setStatusCode(404).end();
            return;
        }
        context.next();
    }
}
