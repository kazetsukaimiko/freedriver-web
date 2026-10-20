package io.freedriver.app.invites;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

@Provider
public class InviteUpstreamMapper implements ExceptionMapper<InviteUpstreamException> {

    @Override
    public Response toResponse(InviteUpstreamException exception) {
        return Response.status(502).type(MediaType.APPLICATION_JSON_TYPE).entity(Map.of("error", "unavailable")).build();
    }
}
