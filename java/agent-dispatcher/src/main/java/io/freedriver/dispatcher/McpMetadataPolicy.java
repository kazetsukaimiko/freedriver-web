package io.freedriver.dispatcher;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityPolicy;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Permits only GET of the protected-resource metadata path.
 * Quarkus copies an exact path permission onto the trailing slash.
 * That copy is denied so it is not a second anonymous response.
 */
@ApplicationScoped
public class McpMetadataPolicy implements HttpSecurityPolicy {

    @Override
    public String name() {
        return "mcp-metadata";
    }

    @Override
    public Uni<CheckResult> checkPermission(
            RoutingContext request, Uni<SecurityIdentity> identity, AuthorizationRequestContext requestContext) {
        String method = request.request().method().name();
        if ("GET".equals(method) && McpProtectedResource.PATH.equals(request.normalizedPath())) {
            return CheckResult.permit();
        }
        return CheckResult.deny();
    }
}
