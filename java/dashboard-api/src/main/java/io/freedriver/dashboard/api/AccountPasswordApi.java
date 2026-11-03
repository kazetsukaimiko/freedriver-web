package io.freedriver.dashboard.api;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Starts Keycloak {@code UPDATE_PASSWORD} for the signed-in person. The dashboard
 * never receives a password. A session alone does not attach one.
 */
@Path("/api/account")
@Produces(MediaType.APPLICATION_JSON)
public interface AccountPasswordApi {

    @GET
    @Path("/password")
    @RolesAllowed({"dashboard", "portal-admin"})
    Response password();
}
