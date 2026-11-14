package io.freedriver.app.jobs;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

@Provider
public class DispatcherUnavailableMapper implements ExceptionMapper<DispatcherUnavailable> {

    @Override
    public Response toResponse(DispatcherUnavailable exception) {
        return Response.status(502).type(MediaType.APPLICATION_JSON_TYPE).entity(Map.of("error", "unavailable")).build();
    }
}
