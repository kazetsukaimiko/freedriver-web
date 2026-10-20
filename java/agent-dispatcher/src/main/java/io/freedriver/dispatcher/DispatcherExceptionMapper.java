package io.freedriver.dispatcher;

import io.freedriver.dispatcher.model.ErrorResponse;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class DispatcherExceptionMapper implements ExceptionMapper<DispatcherException> {

    @Override
    public Response toResponse(DispatcherException exception) {
        return Response.status(status(exception.kind()))
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorResponse(exception.getMessage()))
                .build();
    }

    private static int status(DispatcherException.Kind kind) {
        return switch (kind) {
            case BAD_REQUEST -> 400;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
        };
    }
}
