package io.freedriver.sms.api;

import io.freedriver.sms.security.PhoneRateLimited;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Keycloak's calls into sms. The shared secret and the rate limits are enforced by the app-wide
 * filters in {@code io.freedriver.sms.security}; bodies are validated before the implementation runs.
 */
@Path("/sms")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface SmsApi {

    /** Answers {@code sent} with the request's type and number for every well-formed number, listed or not. */
    @POST
    @Path("/send")
    @PhoneRateLimited(PhoneRateLimited.Kind.SEND)
    SentResponse send(@Valid @NotNull SendRequest request);

    /** Answers {@code verified} when the code matches; any rejected code is {@code CodeRejectedException}. */
    @POST
    @Path("/verify")
    @PhoneRateLimited(PhoneRateLimited.Kind.CODE_CHECK)
    VerifiedResponse verify(@Valid @NotNull VerifyRequest request);
}
