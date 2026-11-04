package io.freedriver.dashboard.api;

import io.freedriver.dashboard.model.PasskeyActionView;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Starts Keycloak {@code webauthn-register-passwordless} for the signed-in
 * person. The dashboard never runs WebAuthn on its own origin.
 */
@Path("/api/account")
@Produces(MediaType.APPLICATION_JSON)
public interface AccountPasskeysApi {

    @GET
    @Path("/passkeys")
    @RolesAllowed({"dashboard", "portal-admin"})
    PasskeyActionView passkeys();
}
