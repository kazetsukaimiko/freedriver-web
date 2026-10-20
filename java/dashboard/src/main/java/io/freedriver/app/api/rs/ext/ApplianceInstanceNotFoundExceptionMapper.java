package io.freedriver.app.api.rs.ext;

import io.freedriver.app.appliances.ApplianceInstanceNotFoundException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class ApplianceInstanceNotFoundExceptionMapper
        implements ExceptionMapper<ApplianceInstanceNotFoundException> {

    @Override
    public Response toResponse(ApplianceInstanceNotFoundException exception) {
        return Response.status(404).build();
    }
}
