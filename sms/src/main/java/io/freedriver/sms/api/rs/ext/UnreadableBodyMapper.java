package io.freedriver.sms.api.rs.ext;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.freedriver.sms.api.ErrorResponse;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Malformed JSON gets the same bare 400; parser messages can echo the body. */
@Provider
public class UnreadableBodyMapper implements ExceptionMapper<JsonProcessingException> {

    @Override
    public Response toResponse(JsonProcessingException exception) {
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(ErrorResponse.BAD_REQUEST)
                .build();
    }
}
