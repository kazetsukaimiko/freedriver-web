package io.freedriver.app.invites;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class InviteFailureMapper implements ExceptionMapper<BadInviteException> {

    @Override
    public Response toResponse(BadInviteException exception) {
        return Response.status(400).type(MediaType.APPLICATION_JSON_TYPE).entity("{}").build();
    }
}
