package io.freedriver.dispatcher;

import io.freedriver.dispatcher.model.ErrorResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Every dispatcher exception the REST layer turns into a status, in one place. */
@ApplicationScoped
public class ErrorMappers {

    @ServerExceptionMapper
    public Response dispatcher(DispatcherException exception) {
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
