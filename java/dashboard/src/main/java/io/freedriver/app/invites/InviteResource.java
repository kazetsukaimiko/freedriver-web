package io.freedriver.app.invites;

import io.freedriver.app.api.LoginLinks;
import io.freedriver.dashboard.api.RateLimitBucket;
import io.freedriver.dashboard.api.RateLimited;
import io.freedriver.sms.validation.UsPhoneNumbers;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * Portal-admin invites. Create takes a phone number, lists it, then creates the Keycloak user.
 * It does not mint a code, send a text, or record the sign-in agreement.
 * The response carries the login link.
 */
@Path("/api/invites")
@RolesAllowed("portal-admin")
@Produces(MediaType.APPLICATION_JSON)
public class InviteResource {

    private final PhoneInviteCreate phones;
    private final LoginLinks links;

    @Inject
    public InviteResource(PhoneInviteCreate phones, LoginLinks links) {
        this.phones = phones;
        this.links = links;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RateLimited(RateLimitBucket.INVITES)
    public Response create(CreateInvite request) {
        String raw = request == null || request.phone() == null ? null : request.phone().strip();
        String phone = UsPhoneNumbers.canonical(raw).orElse(null);
        if (phone == null) {
            return Response.status(400).entity(Map.of("reason", "phone")).build();
        }
        return listed(phone, phones.create(phone, request.name()));
    }

    private Response listed(String phone, PhoneInviteCreate.Result result) {
        return switch (result) {
            case PhoneInviteCreate.Result.Listed listed -> Response.status(201)
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(Map.of("phone", listed.maskedPhone(), "link", links.url(phone)))
                    .build();
            case PhoneInviteCreate.Result.Rejected rejected -> {
                Object body = switch (rejected.status()) {
                    case 409 -> Map.of("reason", "listed");
                    case 502 -> Map.of("error", "unavailable");
                    default -> Map.of("reason", "phone");
                };
                yield Response.status(rejected.status()).type(MediaType.APPLICATION_JSON_TYPE).entity(body).build();
            }
        };
    }

    public record CreateInvite(String name, String phone) {
    }
}
