package io.freedriver.app.security;

import io.freedriver.app.appliances.AppliancesConfig;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * While {@code freedriver.appliances.enabled} is false, every {@code /api/appliances}
 * path returns 404. The default profile leaves it false. The prod profile turns it on
 * for the practice list and still leaves {@code live-commands} false.
 */
@Provider
@Priority(Priorities.AUTHENTICATION - 100)
public class AppliancesDisabledFilter implements ContainerRequestFilter {

    @Inject
    AppliancesConfig config;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (config.enabled()) {
            return;
        }
        String path = requestContext.getUriInfo().getPath();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.equals("api/appliances") || path.startsWith("api/appliances/")) {
            requestContext.abortWith(Response.status(Response.Status.NOT_FOUND).build());
        }
    }
}
