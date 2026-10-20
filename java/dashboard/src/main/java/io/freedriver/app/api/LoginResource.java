package io.freedriver.app.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import java.net.URI;

/**
 * After Keycloak, the browser returns to {@code /login}. Quinoa ignores this path,
 * so a JAX-RS resource sends the session home. The HTTP permission on {@code /login}
 * is the challenge ({@code java-script-auto-redirect} stays false).
 */
@Path("/login")
public class LoginResource {

    @GET
    public Response start() {
        return Response.status(Response.Status.FOUND).location(URI.create("/")).build();
    }
}
