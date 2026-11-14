package io.freedriver.sms.api;

import io.freedriver.sms.model.InviteSend;
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
 * Invite-page codes. The number is not on the list yet. Send stores the agreement, then texts one
 * code: 200 sent; otherwise 400 bad request, 409 already listed, 429 limited, or 503 unavailable.
 * Verify uses the sign-in limits.
 */
@Path("/sms/invite")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface SmsInviteApi {

    @POST
    @Path("send")
    SentResponse send(@Valid @NotNull InviteSend request);

    @POST
    @Path("verify")
    @PhoneRateLimited(PhoneRateLimited.Kind.CODE_CHECK)
    VerifiedResponse verify(@Valid @NotNull VerifyRequest request);
}
