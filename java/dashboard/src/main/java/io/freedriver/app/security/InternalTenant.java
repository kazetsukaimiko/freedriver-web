package io.freedriver.app.security;

import io.quarkus.oidc.TenantResolver;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The {@code internal} OIDC tenant: a {@code service} tenant for autonomy-mcp, which calls the
 * dashboard as the connecting user with that user's token exchanged at Keycloak (audience
 * {@code freedriver-api}). A request resolves here only when it carries a Bearer and its path is
 * {@code /api/mcp} or {@code /api/appliances}. Every other request, including a cookie session on
 * those paths, stays on the default web-app tenant. {@code @RolesAllowed} on the APIs then judges
 * the user's own roles.
 */
@ApplicationScoped
public class InternalTenant implements TenantResolver {

    public static final String ID = "internal";

    @Override
    public String resolve(RoutingContext context) {
        return applies(context) ? ID : null;
    }

    /** True for a Bearer request on an internal path. */
    static boolean applies(RoutingContext context) {
        return BearerHeaders.hasBearer(context.request().getHeader("Authorization"))
                && internalPath(context.normalizedPath());
    }

    static boolean internalPath(String path) {
        return path != null && (under(path, "/api/mcp") || under(path, "/api/appliances"));
    }

    /** True when quarkus-oidc authenticated this identity through the internal tenant. */
    static boolean authenticated(SecurityIdentity identity) {
        return identity != null && ID.equals(identity.getAttribute("tenant-id"));
    }

    private static boolean under(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
