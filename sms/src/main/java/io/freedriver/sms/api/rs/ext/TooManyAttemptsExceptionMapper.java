package io.freedriver.sms.api.rs.ext;

import io.freedriver.sms.model.ErrorResponse;
import io.freedriver.sms.otp.TooManyAttemptsException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** 429 with {@code Retry-After} set to the per-number window, the same header a send limit uses. */
@Provider
public class TooManyAttemptsExceptionMapper implements ExceptionMapper<TooManyAttemptsException> {

    @Override
    public Response toResponse(TooManyAttemptsException exception) {
        return Response.status(Response.Status.TOO_MANY_REQUESTS)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfter().toSeconds()))
                .entity(ErrorResponse.tooManyAttempts())
                .build();
    }
}
