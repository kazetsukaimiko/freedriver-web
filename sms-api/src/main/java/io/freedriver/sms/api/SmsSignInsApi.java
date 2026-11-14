package io.freedriver.sms.api;

import io.freedriver.sms.model.SignInRecords;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** The stored sign-in times. sms owns {@code sign-ins}; callers present the shared secret. */
@Path("/sms/sign-ins")
@Produces(MediaType.APPLICATION_JSON)
public interface SmsSignInsApi {

    @GET
    SignInRecords list();
}
