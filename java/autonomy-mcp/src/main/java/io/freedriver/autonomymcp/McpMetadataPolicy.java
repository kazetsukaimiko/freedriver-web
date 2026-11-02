package io.freedriver.autonomymcp;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityPolicy;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;

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
