package io.freedriver.sms.twilio;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * The three Twilio Verify v2 calls sms makes. Client only. {@link TwilioBasicAuth} adds the API
 * key; a null form field is left out of the body.
 */
@RegisterRestClient(configKey = "twilio-verify")
@Path("/v2/Services/{serviceSid}")
@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
@Produces(MediaType.APPLICATION_JSON)
public interface TwilioVerifyApi {

    /** Twilio generates and texts the code. */
    @POST
    @Path("/Verifications")
    TwilioVerification create(@PathParam("serviceSid") String serviceSid,
                              @FormParam("To") String to,
                              @FormParam("Channel") String channel);

    @POST
    @Path("/VerificationCheck")
    TwilioVerification check(@PathParam("serviceSid") String serviceSid,
                             @FormParam("To") String to,
                             @FormParam("Code") String code);

    /** Updates one verification, including setting its status to {@code canceled}. */
    @POST
    @Path("/Verifications/{verificationSid}")
    TwilioVerification update(@PathParam("serviceSid") String serviceSid,
                              @PathParam("verificationSid") String verificationSid,
                              @FormParam("Status") String status);
}
