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
        return Response.status(exception.kind().status())
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorResponse(exception.getMessage()))
                .build();
    }
}
