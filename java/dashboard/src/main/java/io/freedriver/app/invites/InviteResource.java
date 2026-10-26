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

import java.util.Map;
import java.util.UUID;

/**
 * Portal-admin invites. A name still mints one link and does not list a number.
 * A phone lists the number, then creates the Keycloak user, and does not mint a link.
 */
@Path("/api/invites")
@RolesAllowed("portal-admin")
@Produces(MediaType.APPLICATION_JSON)
public class InviteResource {

    private final InviteStore store;
    private final PhoneInviteCreate phones;
    private final String publicBase;

    @Inject
    public InviteResource(InviteStore store,
                          PhoneInviteCreate phones,
                          @ConfigProperty(name = "freedriver.public-base-url",
                                  defaultValue = "https://app.freedriver.io") String publicBase) {
        this.store = store;
        this.phones = phones;
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
        if (request != null && request.phone() != null) {
            return listed(phones.create(request.phone(), request.name()));
        }
        InviteStore.Created created = store.create(request == null ? null : request.name());
        String link = publicBase + "/invite/#" + created.code();
        return Response.status(201).entity(new InviteCreated(created.invite(), link)).build();
    }

    private static Response listed(PhoneInviteCreate.Result result) {
        return switch (result) {
            case PhoneInviteCreate.Result.Listed listed -> Response.status(201)
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(Map.of("phone", listed.maskedPhone()))
                    .build();
            case PhoneInviteCreate.Result.Rejected rejected -> {
                Object body = rejected.status() == 502 ? Map.of("error", "unavailable") : Map.of();
                yield Response.status(rejected.status()).type(MediaType.APPLICATION_JSON_TYPE).entity(body).build();
            }
        };
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

    public record CreateInvite(String name, String phone) {
    }

    public record InviteCreated(InviteView invite, String link) {
    }
}
