package io.freedriver.sms.api.rs.ext;

import io.freedriver.sms.api.ErrorResponse;
import io.freedriver.sms.security.RateLimitedException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** 429 with {@code Retry-After} set to the full window, the same for every number. */
@Provider
public class RateLimitedExceptionMapper implements ExceptionMapper<RateLimitedException> {

    @Override
    public Response toResponse(RateLimitedException exception) {
        return Response.status(Response.Status.TOO_MANY_REQUESTS)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfter().toSeconds()))
                .entity(ErrorResponse.RATE_LIMITED)
                .build();
    }
}
