package io.freedriver.app.invites;

import io.freedriver.dashboard.api.RateLimited;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.UUID;

/**
 * Portal-admin invite links. Creating one does not put a number on the phone list.
 * The code is returned once, in the URL fragment of the link.
 */
@Path("/api/invites")
@RolesAllowed("portal-admin")
@Produces(MediaType.APPLICATION_JSON)
public class InviteResource {

    private final InviteStore store;
    private final String publicBase;

    @Inject
    public InviteResource(InviteStore store,
                          @ConfigProperty(name = "freedriver.public-base-url",
                                  defaultValue = "https://app.freedriver.io") String publicBase) {
        this.store = store;
        this.publicBase = publicBase.endsWith("/") ? publicBase.substring(0, publicBase.length() - 1) : publicBase;
    }

    @GET
    public InviteList list() {
        return new InviteList(store.open(), null);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RateLimited("invites")
    public Response create(CreateInvite request) {
        InviteStore.Created created = store.create(request == null ? null : request.name());
        String link = publicBase + "/invite/#" + created.code();
        return Response.status(201).entity(new InviteCreated(created.invite(), link)).build();
    }

    @POST
    @Path("{id}/cancel")
    @RateLimited("invites")
    public Response cancel(@PathParam("id") UUID id) {
        if (!store.cancel(id)) {
            return Response.status(404).type(MediaType.APPLICATION_JSON_TYPE).entity("{}").build();
        }
        return Response.noContent().build();
    }

    public record CreateInvite(String name) {
    }

    public record InviteCreated(InviteView invite, String link) {
    }
}
