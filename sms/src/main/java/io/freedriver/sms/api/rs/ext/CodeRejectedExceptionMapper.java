package io.freedriver.sms.api.rs.ext;

import io.freedriver.sms.model.ErrorResponse;
import io.freedriver.sms.otp.CodeRejectedException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** {@code 400 {"error":"invalid-code"}}, plus {@code reason} when the check names one. */
@Provider
public class CodeRejectedExceptionMapper implements ExceptionMapper<CodeRejectedException> {

    @Override
    public Response toResponse(CodeRejectedException exception) {
        ErrorResponse body = exception.reason() == null
                ? ErrorResponse.INVALID_CODE
                : ErrorResponse.rejected(exception.reason());
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(body)
                .build();
    }
}
