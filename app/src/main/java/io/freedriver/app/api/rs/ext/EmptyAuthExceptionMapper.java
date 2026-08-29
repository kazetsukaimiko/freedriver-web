package io.freedriver.app.api.rs.ext;

import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.util.Map;

/**
 * 401/403 on the API stay empty JSON. No names, no login HTML.
 */
public class EmptyAuthExceptionMapper {

    @ServerExceptionMapper(value = UnauthorizedException.class, priority = 1)
    public Response unauthorized(UnauthorizedException ignored) {
        return empty(Response.Status.UNAUTHORIZED);
    }

    @ServerExceptionMapper(value = ForbiddenException.class, priority = 1)
    public Response forbidden(ForbiddenException ignored) {
        return empty(Response.Status.FORBIDDEN);
    }

    private static Response empty(Response.Status status) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(Map.of())
                .build();
    }
}
