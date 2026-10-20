package io.freedriver.dispatcher;

import io.freedriver.dispatcher.model.JsonRpcError;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class McpProtocolExceptionMapper implements ExceptionMapper<McpProtocolException> {

    @Override
    public Response toResponse(McpProtocolException exception) {
        return switch (exception.outcome()) {
            case ACCEPTED -> Response.accepted().build();
            case NOT_ALLOWED -> Response.status(Response.Status.METHOD_NOT_ALLOWED)
                    .header("Allow", "POST, DELETE")
                    .build();
            case PARSE, INVALID, NOT_FOUND, FORBIDDEN, METHOD_NOT_FOUND, INVALID_PARAMS -> json(exception);
        };
    }

    private static Response json(McpProtocolException exception) {
        JsonRpcError body = new JsonRpcError(
                "2.0",
                exception.id(),
                new JsonRpcError.Body(exception.outcome().code(), exception.detail()));
        return Response.status(exception.outcome().status())
                .type(MediaType.APPLICATION_JSON)
                .entity(body)
                .build();
    }
}
