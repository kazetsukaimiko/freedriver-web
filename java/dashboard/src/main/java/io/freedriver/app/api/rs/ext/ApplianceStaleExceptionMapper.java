package io.freedriver.app.api.rs.ext;

import io.freedriver.app.appliances.ApplianceControl;
import io.freedriver.app.appliances.ApplianceService;
import io.freedriver.app.appliances.ApplianceSnapshot;
import io.freedriver.app.appliances.ApplianceStaleException;
import io.freedriver.app.appliances.InstanceView;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.List;

@Provider
public class ApplianceStaleExceptionMapper implements ExceptionMapper<ApplianceStaleException> {

    @Inject
    ApplianceService appliances;

    @Inject
    ApplianceControl control;

    @Override
    public Response toResponse(ApplianceStaleException exception) {
        ApplianceSnapshot snapshot = exception.snapshot().orElse(new ApplianceSnapshot(null, List.of()));
        String name = exception.instanceId() == null
                ? null
                : control.instanceName(exception.instanceId()).orElse(null);
        InstanceView body = appliances.toView(exception.instanceId(), name, snapshot, true, false);
        return Response.status(409).type(MediaType.APPLICATION_JSON).entity(body).build();
    }
}
