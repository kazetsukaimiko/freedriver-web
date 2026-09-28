package io.freedriver.sms.api;

import io.freedriver.sms.otp.OtpService;
import io.freedriver.sms.otp.VerifyResult;
import io.freedriver.sms.security.PhoneRateLimited;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Keycloak's calls into sms. The shared secret and the rate limits are enforced by the app-wide
 * filters in {@code io.freedriver.sms.security}; request bodies are validated before this runs.
 */
@Path("/otp")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class OtpResource {

    private final OtpService otp;

    @Inject
    public OtpResource(OtpService otp) {
        this.otp = otp;
    }

    /** Answers {@code sent} for every well-formed number, listed or not. */
    @POST
    @Path("/send")
    @PhoneRateLimited(PhoneRateLimited.Kind.SEND)
    public SentResponse send(@Valid @NotNull SendRequest request) {
        otp.send(request.phone());
        return SentResponse.SENT;
    }

    @POST
    @Path("/verify")
    @PhoneRateLimited(PhoneRateLimited.Kind.CODE_CHECK)
    public Response verify(@Valid @NotNull VerifyRequest request) {
        VerifyResult result = otp.verify(request.phone(), request.code());
        return switch (result.status()) {
            case VERIFIED -> Response.ok(new VerifiedResponse(result.username())).build();
            case INVALID_CODE -> Response.status(Response.Status.BAD_REQUEST).entity(ErrorResponse.INVALID_CODE).build();
            case UNAVAILABLE -> Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(ErrorResponse.UNAVAILABLE).build();
        };
    }
}
