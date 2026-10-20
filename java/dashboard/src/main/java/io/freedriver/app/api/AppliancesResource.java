package io.freedriver.app.api;

import io.freedriver.dashboard.api.AppliancesApi;
import io.freedriver.dashboard.model.ApplianceCommandRequest;
import io.freedriver.dashboard.model.ApplianceMapResponse;
import io.freedriver.app.appliances.ApplianceService;
import io.freedriver.dashboard.model.InstanceView;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Path;

import java.util.UUID;

@ApplicationScoped
@Path("/api/appliances")
public class AppliancesResource implements AppliancesApi {

    @Inject
    ApplianceService appliances;

    @Override
    public ApplianceMapResponse list() {
        return appliances.currentMap();
    }

    @Override
    public InstanceView command(UUID instanceId, String applianceName, ApplianceCommandRequest body) {
        return appliances.issueCommand(instanceId, applianceName, body.getOn());
    }
}
