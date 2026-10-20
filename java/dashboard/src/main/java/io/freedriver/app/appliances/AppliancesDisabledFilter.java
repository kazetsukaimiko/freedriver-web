package io.freedriver.app.appliances;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Default/prod: appliances API is inactive (404) so an unauthenticated live command
 * route is never exposed while OIDC is off.
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
