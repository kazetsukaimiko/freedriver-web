package io.freedriver.app.invites;

import io.freedriver.app.phones.PhoneRow;
import io.freedriver.app.phones.SmsPhoneList;
import io.freedriver.app.phones.UsPhones;
import io.freedriver.app.security.RateLimitedException;
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
import org.jboss.logging.Logger;

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

    private static final Logger LOG = Logger.getLogger(InviteResource.class);

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
        return invitation(phone);
    }

    /** One invitation text. Create does not mint a code and does not write the agreement. */
    private Response invitation(String phone) {
        SmsPhoneList.Invitation result = sms.sendInvitation(phone);
        return switch (result) {
            case SENT -> Response.status(201).entity(new InvitationCreated(phone)).build();
            case LIMITED -> throw new RateLimitedException();
            case UNAVAILABLE -> {
                LOG.warnf("Invitation text failed for %s", PhoneLog.mask(phone));
                throw new InviteUpstreamException("invitation text was not sent");
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

    /** {@code {"sent":{"type":"invite","phone":"+1..."}}}. The login link stays in the text. */
    public record InvitationCreated(Sent sent) {
        public InvitationCreated(String phone) {
            this(new Sent("invite", phone));
        }

        public record Sent(String type, String phone) {
        }
    }
}
