package io.freedriver.app.phones;

import io.quarkus.oidc.client.filter.OidcClientFilter;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * The SMS SPI's invite-account resource. The {@code invite} OIDC client supplies the
 * {@code freedriver-invite} service-account token.
 */
@RegisterRestClient(configKey = "keycloak-invite")
@OidcClientFilter("invite")
@RegisterProvider(InviteAccountResponses.class)
@Path("/realms/{realm}/freedriver-invite")
public interface InviteAccountsApi {

    @POST
    @Path("/users")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    InviteAccount create(@PathParam("realm") String realm, InviteAccountRequest request);

    /** The phone the SPI creates the user for. Nothing else is sent. */
    record InviteAccountRequest(String phone) {
    }

    /** The user the SPI created, its groups, and any admin roles it holds. */
    record InviteAccount(String username, java.util.List<String> groups, java.util.List<String> adminRoles) {
    }
}
