package io.freedriver.mcp;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityPolicy;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Permits only GET of the protected-resource metadata path. Quarkus copies an exact path permission
 * onto the trailing slash; that copy is denied so it is not a second anonymous response.
 */
@ApplicationScoped
public class McpMetadataPolicy implements HttpSecurityPolicy {

    private final McpEndpoint endpoint;

    @Inject
    public McpMetadataPolicy(McpEndpoint endpoint) {
        this.endpoint = endpoint;
    }

    @Override
    public String name() {
        return "mcp-metadata";
    }

    @Override
    public Uni<CheckResult> checkPermission(
            RoutingContext request, Uni<SecurityIdentity> identity, AuthorizationRequestContext requestContext) {
        String method = request.request().method().name();
        if ("GET".equals(method) && endpoint.metadataPath().equals(request.normalizedPath())) {
            return CheckResult.permit();
        }
        return CheckResult.deny();
    }
}
