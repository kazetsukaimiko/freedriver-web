package io.freedriver.app.security;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class RateLimitedExceptionMapper implements ExceptionMapper<RateLimitedException> {

    @Override
    public Response toResponse(RateLimitedException exception) {
        return Response.status(429).build();
    }
}
