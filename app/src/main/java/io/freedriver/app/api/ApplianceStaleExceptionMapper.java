package io.freedriver.app.api;

import io.freedriver.app.appliances.ApplianceMapResponse;
import io.freedriver.app.appliances.ApplianceStaleException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.List;

@Provider
public class ApplianceStaleExceptionMapper implements ExceptionMapper<ApplianceStaleException> {

    @Override
    public Response toResponse(ApplianceStaleException exception) {
        ApplianceMapResponse body = exception.snapshot()
                .map(snapshot -> snapshot.toResponse(true, false))
                .orElseGet(() -> new ApplianceMapResponse(null, true, false, List.of()));
        return Response.status(409).type(MediaType.APPLICATION_JSON).entity(body).build();
    }
}
