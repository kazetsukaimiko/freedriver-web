package io.freedriver.sms.api.rs.ext;

import io.freedriver.sms.api.ErrorResponse;
import io.freedriver.sms.otp.CodeRejectedException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** One answer for every rejected code: {@code 400 {"error":"invalid-code"}}. */
@Provider
public class CodeRejectedExceptionMapper implements ExceptionMapper<CodeRejectedException> {

    @Override
    public Response toResponse(CodeRejectedException exception) {
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(ErrorResponse.INVALID_CODE)
                .build();
    }
}
