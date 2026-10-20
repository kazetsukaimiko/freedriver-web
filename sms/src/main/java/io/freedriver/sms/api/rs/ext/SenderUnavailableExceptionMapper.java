package io.freedriver.sms.api.rs.ext;

import io.freedriver.sms.api.ErrorResponse;
import io.freedriver.sms.otp.SenderUnavailableException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class SenderUnavailableExceptionMapper implements ExceptionMapper<SenderUnavailableException> {

    @Override
    public Response toResponse(SenderUnavailableException exception) {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(ErrorResponse.UNAVAILABLE)
                .build();
    }
}
