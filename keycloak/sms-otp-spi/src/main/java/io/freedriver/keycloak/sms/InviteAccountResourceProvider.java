package io.freedriver.keycloak.sms;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.keycloak.models.KeycloakSession;
import org.keycloak.services.resource.RealmResourceProvider;

/**
 * RealmResourceSPI instance and JAX-RS subresource. RESTEasy Reactive indexes
 * {@code POST /users} on the runtime class {@code getResource()} returns; a
 * separate resource object is not in that index (HTTP 405). Class-level
 * {@code @Path} on a provider JAR resource 405s under Keycloak's Quarkus REST.
 */
public final class InviteAccountResourceProvider extends InviteAccountResource
        implements RealmResourceProvider {

    InviteAccountResourceProvider(KeycloakSession session) {
        super(session);
    }

    @POST
    @Path("users")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Override
    public Response create(CreateUser body) {
        return super.create(body);
    }

    @Override
    public Object getResource() {
        return this;
    }

    @Override
    public void close() {
    }
}
