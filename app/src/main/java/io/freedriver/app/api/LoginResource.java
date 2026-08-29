package io.freedriver.app.api;

import io.quarkus.security.Authenticated;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import java.net.URI;

/**
 * OIDC start. Anonymous document hits land here; {@code @Authenticated} challenges
 * Keycloak when OIDC is on. XHR still uses {@code X-Requested-With} 401 on the API.
 */
@Path("/login")
public class LoginResource {

    @GET
    @Authenticated
    public Response start() {
        return Response.status(Response.Status.FOUND).location(URI.create("/")).build();
    }
}
