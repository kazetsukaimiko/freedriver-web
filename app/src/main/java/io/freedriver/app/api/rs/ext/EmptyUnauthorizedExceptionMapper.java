package io.freedriver.app.api.rs.ext;

import io.quarkus.security.UnauthorizedException;
import jakarta.annotation.Priority;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

/**
 * 401 on the API stays empty JSON. No names, no login HTML.
 */
@Provider
@Priority(1)
public class EmptyUnauthorizedExceptionMapper implements ExceptionMapper<UnauthorizedException> {

    @Override
    public Response toResponse(UnauthorizedException exception) {
        return Response.status(Response.Status.UNAUTHORIZED)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(Map.of())
                .build();
    }
}
