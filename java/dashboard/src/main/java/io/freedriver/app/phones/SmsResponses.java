package io.freedriver.app.phones;

import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

/** Any sms status of 400 or more becomes {@link SmsRefused}. The body is not read. */
public class SmsResponses implements ResponseExceptionMapper<SmsRefused> {

    @Override
    public SmsRefused toThrowable(Response response) {
        return new SmsRefused(response.getStatus());
    }
}
