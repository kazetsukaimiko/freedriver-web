package io.freedriver.sms.api.rs.ext;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.freedriver.sms.model.ErrorResponse;
import io.freedriver.sms.otp.CodeRejectedException;
import io.freedriver.sms.otp.SenderUnavailableException;
import io.freedriver.sms.otp.TooManyAttemptsException;
import io.freedriver.sms.security.RateLimitedException;
import io.freedriver.sms.security.UnauthorizedCallerException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Every sms exception the REST layer turns into a status, in one place. The bodies are the
 * {@link ErrorResponse} values {@code PhoneRateLimitFilter} reads back.
 */
@ApplicationScoped
public class ErrorMappers {

    /** {@code 400 {"error":"invalid-code"}}, plus {@code reason} when the check names one. */
    @ServerExceptionMapper
    public Response codeRejected(CodeRejectedException exception) {
        ErrorResponse body = exception.reason() == null
                ? ErrorResponse.INVALID_CODE
                : ErrorResponse.rejected(exception.reason());
        return json(Response.Status.BAD_REQUEST, body).build();
    }

    /** 429 with {@code Retry-After} set to the full window, the same for every number. */
    @ServerExceptionMapper
    public Response rateLimited(RateLimitedException exception) {
        return json(Response.Status.TOO_MANY_REQUESTS, ErrorResponse.RATE_LIMITED)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfter().toSeconds()))
                .build();
    }

    /** 429 with {@code Retry-After} set to the per-number window, the same header a send limit uses. */
    @ServerExceptionMapper
    public Response tooManyAttempts(TooManyAttemptsException exception) {
        return json(Response.Status.TOO_MANY_REQUESTS, ErrorResponse.tooManyAttempts())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfter().toSeconds()))
                .build();
    }

    @ServerExceptionMapper
    public Response senderUnavailable(SenderUnavailableException exception) {
        return json(Response.Status.SERVICE_UNAVAILABLE, ErrorResponse.UNAVAILABLE).build();
    }

    @ServerExceptionMapper
    public Response unauthorizedCaller(UnauthorizedCallerException exception) {
        return json(Response.Status.UNAUTHORIZED, ErrorResponse.UNAUTHORIZED).build();
    }

    /** Malformed JSON gets the same bare 400; parser messages can echo the body. */
    @ServerExceptionMapper
    public Response unreadableBody(JsonProcessingException exception) {
        return json(Response.Status.BAD_REQUEST, ErrorResponse.BAD_REQUEST).build();
    }

    /** A request that fails validation gets a bare 400 that does not echo the submitted values. */
    @ServerExceptionMapper
    public Response invalid(ConstraintViolationException exception) {
        return json(Response.Status.BAD_REQUEST, ErrorResponse.BAD_REQUEST).build();
    }

    private static Response.ResponseBuilder json(Response.Status status, ErrorResponse body) {
        return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE).entity(body);
    }
}
