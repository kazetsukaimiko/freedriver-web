package io.freedriver.sms.http;

import io.freedriver.sms.model.SentResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * One invitation text. The shared secret is the app-wide caller filter.
 * The per-number send limit and the daily cap run in the implementation, on a Messages API
 * send that goes out while the sign-in agreement is still unwritten.
 */
@Path("/sms/invite/text")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface InvitationTextApi {

    @POST
    SentResponse text(@Valid @NotNull InvitationRequest request);
}
