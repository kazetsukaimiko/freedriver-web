package io.freedriver.app.phones;

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

    @Inject
    public PhoneRemovalResource(SmsPhoneList sms, PhoneSignInDirectory keycloak) {
        this.sms = sms;
        this.keycloak = keycloak;
    }

    @GET
    public PhoneList list() {
        return new PhoneList(sms.list());
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
        boolean dropped = sms.remove(phone);
        boolean membership = keycloak.removeFromPhoneSignIn(phone);
        if (!dropped && !membership) {
            return Response.status(404).entity("{}").build();
        }
        return Response.noContent().build();
    }

    public record PhoneList(List<PhoneRow> phones) {
    }

    public record RemovePhone(String phone) {
    }
}
