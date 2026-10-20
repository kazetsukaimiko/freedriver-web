package io.freedriver.dashboard.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

/**
 * After Keycloak, the browser returns to {@code /login}. Quinoa ignores this path,
 * so a JAX-RS resource sends the session home. The HTTP permission on {@code /login}
 * is the challenge ({@code java-script-auto-redirect} stays false).
 */
@Path("/login")
public interface LoginApi {

    @GET
    Response start();
}
