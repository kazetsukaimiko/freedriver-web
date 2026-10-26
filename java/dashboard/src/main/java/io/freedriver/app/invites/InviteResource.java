package io.freedriver.app.invites;

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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.UUID;

/**
 * Portal-admin invite links. Creating one does not put a number on the phone list.
 * The code is returned once, in the URL fragment of the link.
 */
@Path("/api/invites")
@RolesAllowed("portal-admin")
@Produces(MediaType.APPLICATION_JSON)
public class InviteResource {

    private static final Logger LOG = Logger.getLogger(InviteResource.class);

    private final InviteStore store;
    private final SmsPhoneList sms;
    private final String publicBase;

    @Inject
    public InviteResource(InviteStore store,
                          SmsPhoneList sms,
                          @ConfigProperty(name = "freedriver.public-base-url",
                                  defaultValue = "https://app.freedriver.io") String publicBase) {
        this.store = store;
        this.sms = sms;
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
        if (request != null && request.phone() != null && !request.phone().isBlank()) {
            return invitation(request.phone());
        }
        InviteStore.Created created = store.create(request == null ? null : request.name());
        String link = publicBase + "/invite/#" + created.code();
        return Response.status(201).entity(new InviteCreated(created.invite(), link)).build();
    }

    /** One invitation text for a phone create. The link invite stays the name path. */
    private Response invitation(String rawPhone) {
        Optional<String> phone = UsPhones.canonical(rawPhone);
        if (phone.isEmpty()) {
            throw new BadInviteException();
        }
        SmsPhoneList.Invitation result = sms.sendInvitation(phone.get());
        return switch (result) {
            case SENT -> Response.status(201).entity(new InvitationCreated(phone.get())).build();
            case LIMITED -> throw new RateLimitedException();
            case UNAVAILABLE -> {
                LOG.warnf("Invitation text failed for %s", PhoneLog.mask(phone.get()));
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

    public record CreateInvite(String name, String phone) {
    }

    public record InviteCreated(InviteView invite, String link) {
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
