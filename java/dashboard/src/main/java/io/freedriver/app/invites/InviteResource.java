package io.freedriver.app.invites;

import io.freedriver.app.phones.PhoneRow;
import io.freedriver.app.phones.SmsPhoneList;
import io.freedriver.app.phones.UsPhones;
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

import java.util.Map;
import java.util.UUID;

/**
 * Portal-admin invites. Create takes a phone number and does not mint a code.
 * A code already in the store still lists, cancels, and redeems until it expires.
 */
@Path("/api/invites")
@RolesAllowed("portal-admin")
@Produces(MediaType.APPLICATION_JSON)
public class InviteResource {

    private final InviteStore store;
    private final SmsPhoneList sms;

    @Inject
    public InviteResource(InviteStore store, SmsPhoneList sms) {
        this.store = store;
        this.sms = sms;
    }

    @GET
    public InviteList list() {
        return new InviteList(store.open(), null);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RateLimited("invites")
    public Response create(CreateInvite request) {
        String raw = request == null || request.phone() == null ? null : request.phone().strip();
        String phone = UsPhones.canonical(raw).orElse(null);
        if (phone == null) {
            return Response.status(400).entity(Map.of("reason", "phone")).build();
        }
        if (listed(phone)) {
            return Response.status(409).entity(Map.of("reason", "listed")).build();
        }
        return Response.status(201).entity(Map.of()).build();
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

    private boolean listed(String phone) {
        for (PhoneRow row : sms.list()) {
            String stored = row.phone() == null ? "" : row.phone().strip();
            if (phone.equals(UsPhones.canonical(stored).orElse(stored))) {
                return true;
            }
        }
        return false;
    }

    public record CreateInvite(String phone) {
    }
}
