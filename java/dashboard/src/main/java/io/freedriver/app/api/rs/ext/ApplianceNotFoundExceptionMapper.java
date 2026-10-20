package io.freedriver.app.api.rs.ext;

import io.freedriver.app.appliances.ApplianceNotFoundException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class ApplianceNotFoundExceptionMapper implements ExceptionMapper<ApplianceNotFoundException> {

    @Override
    public Response toResponse(ApplianceNotFoundException exception) {
        return Response.status(404).build();
    }
}
