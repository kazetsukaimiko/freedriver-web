package io.freedriver.app.phones;

import io.freedriver.app.api.LoginLinks;
import io.freedriver.dashboard.api.RateLimited;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Portal-admin phone list. Removal takes the number off sms and out of Keycloak {@code phone-sign-in}.
 */
@Path("/api/phones")
@RolesAllowed("portal-admin")
@Produces(MediaType.APPLICATION_JSON)
public class PhoneRemovalResource {

    static final Pattern PHONE = Pattern.compile("^\\+1[0-9]{10}$");

    private final SmsPhoneList sms;
    private final PhoneSignInDirectory keycloak;
    private final LoginLinks links;

    @Inject
    public PhoneRemovalResource(SmsPhoneList sms, PhoneSignInDirectory keycloak, LoginLinks links) {
        this.sms = sms;
        this.keycloak = keycloak;
        this.links = links;
    }

    @GET
    public PhoneList list() {
        return new PhoneList(sms.list().stream().map(this::listed).toList());
    }

    /** The login link stays until the first recorded sign-in. A number that cannot be a link has none. */
    private ListedPhone listed(PhoneRow row) {
        String link = null;
        if (row.lastSignIn() == null) {
            try {
                link = links.url(row.phone());
            } catch (IllegalArgumentException ignored) {
                link = null;
            }
        }
        return new ListedPhone(row.phone(), row.name(), row.role(), row.addedAt(), row.consentedAt(),
                row.lastSignIn(), link);
    }

    @POST
    @Path("remove")
    @Consumes(MediaType.APPLICATION_JSON)
    @RateLimited("invites")
    public Response remove(RemovePhone request) {
        String phone = request == null ? null : request.phone();
        if (phone == null || !PHONE.matcher(phone).matches()) {
            return Response.status(400).entity("{}").build();
        }
        // Blank secret fails closed before Keycloak. A failed group removal must leave the sms row in place.
        sms.requireSharedSecret();
        boolean membership = keycloak.removeFromPhoneSignIn(phone);
        boolean dropped = sms.remove(phone);
        if (!dropped && !membership) {
            return Response.status(404).entity("{}").build();
        }
        return Response.noContent().build();
    }

    public record PhoneList(List<ListedPhone> phones) {
    }

    public record ListedPhone(
            String phone,
            String name,
            String role,
            Instant addedAt,
            Instant consentedAt,
            Instant lastSignIn,
            String loginLink) {
    }

    public record RemovePhone(String phone) {
    }
}
