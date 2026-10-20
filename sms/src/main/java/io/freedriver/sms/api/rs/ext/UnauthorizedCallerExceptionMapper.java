package io.freedriver.sms.api.rs.ext;

import io.freedriver.sms.api.ErrorResponse;
import io.freedriver.sms.security.UnauthorizedCallerException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class UnauthorizedCallerExceptionMapper implements ExceptionMapper<UnauthorizedCallerException> {

    @Override
    public Response toResponse(UnauthorizedCallerException exception) {
        return Response.status(Response.Status.UNAUTHORIZED)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(ErrorResponse.UNAUTHORIZED)
                .build();
    }
}
