package io.freedriver.sms.api;

import io.freedriver.sms.model.SendRequest;
import io.freedriver.sms.model.SentResponse;
import io.freedriver.sms.model.VerifiedResponse;
import io.freedriver.sms.model.VerifyRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Keycloak's calls into sms. The shared secret is enforced by the app-wide filter in
 * {@code io.freedriver.sms.security}; bodies are validated before the implementation runs, and the
 * implementation's methods carry the per-number rate limits.
 */
@Path("/sms")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface SmsApi {

    /** Answers {@code sent} with the request's type and number for every well-formed number, listed or not. */
    @POST
    @Path("/send")
    SentResponse send(@Valid @NotNull SendRequest request);

    /**
     * Answers {@code verified} when the code matches. A rejected code throws
     * {@code CodeRejectedException}. The check after the fifth attempt throws
     * {@code TooManyAttemptsException}.
     */
    @POST
    @Path("/verify")
    VerifiedResponse verify(@Valid @NotNull VerifyRequest request);
}
