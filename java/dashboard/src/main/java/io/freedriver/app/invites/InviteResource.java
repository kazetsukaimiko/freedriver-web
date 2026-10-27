package io.freedriver.app.invites;

import io.freedriver.app.phones.PhoneSignInDirectory;
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
 * Portal-admin invites. Create takes a phone number, lists it, then creates the Keycloak user,
 * then sends one invitation text. It does not mint a code and does not write the agreement.
 * A name alone does not mint one either.
 * A code already in the store still lists, cancels, and redeems until it expires.
 */
@Path("/api/invites")
@RolesAllowed("portal-admin")
@Produces(MediaType.APPLICATION_JSON)
public class InviteResource {

    private static final Logger LOG = Logger.getLogger(InviteResource.class);

    private final InviteStore store;
    private final PhoneInviteCreate phones;
    private final SmsPhoneList sms;
    private final PhoneSignInDirectory keycloak;

    @Inject
    public InviteResource(InviteStore store, PhoneInviteCreate phones, SmsPhoneList sms, PhoneSignInDirectory keycloak) {
        this.store = store;
        this.phones = phones;
        this.sms = sms;
        this.keycloak = keycloak;
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
        PhoneInviteCreate.Result result = phones.create(phone, request.name());
        if (result instanceof PhoneInviteCreate.Result.Rejected rejected) {
            return listed(rejected);
        }
        return invitation(phone, ((PhoneInviteCreate.Result.Listed) result).maskedPhone());
    }

    /** One invitation text after the number is listed. Create does not write the agreement. */
    private Response invitation(String phone, String masked) {
        SmsPhoneList.Invitation result;
        try {
            result = sms.sendInvitation(phone);
        } catch (RuntimeException e) {
            undo(phone);
            LOG.warnf("Invitation text failed for %s", PhoneLog.mask(phone));
            throw new InviteUpstreamException("invitation text was not sent");
        }
        return switch (result) {
            case SENT -> Response.status(201)
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(InvitationCreated.of(phone, masked))
                    .build();
            case LIMITED -> {
                undo(phone);
                throw new RateLimitedException();
            }
            case UNAVAILABLE -> {
                undo(phone);
                LOG.warnf("Invitation text failed for %s", PhoneLog.mask(phone));
                throw new InviteUpstreamException("invitation text was not sent");
            }
        };
    }

    private void undo(String phone) {
        try {
            sms.remove(phone);
        } catch (RuntimeException e) {
            LOG.warnf("Invite list rollback failed for %s", PhoneLog.mask(phone));
        }
        try {
            keycloak.removeFromPhoneSignIn(phone);
        } catch (RuntimeException e) {
            LOG.warnf("Invite account rollback failed for %s", PhoneLog.mask(phone));
        }
    }

    private static Response listed(PhoneInviteCreate.Result.Rejected rejected) {
        Object body = switch (rejected.status()) {
            case 409 -> Map.of("reason", "listed");
            case 502 -> Map.of("error", "unavailable");
            default -> Map.of("reason", "phone");
        };
        return Response.status(rejected.status()).type(MediaType.APPLICATION_JSON_TYPE).entity(body).build();
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

    /** Masked {@code phone} plus {@code {"sent":{"type":"invite","phone":"+1..."}}}. The login link stays in the text. */
    public record InvitationCreated(String phone, Sent sent) {
        public static InvitationCreated of(String phone, String masked) {
            return new InvitationCreated(masked, new Sent("invite", phone));
        }

        public record Sent(String type, String phone) {
        }
    }
}
