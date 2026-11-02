package io.freedriver.app.api;

import io.freedriver.app.appliances.ApplianceService;
import io.freedriver.app.appliances.MqttLiveClient;
import io.freedriver.dashboard.api.McpOpsApi;
import io.freedriver.dashboard.model.ApplianceMapResponse;
import io.freedriver.dashboard.model.HomeStatusResponse;
import io.freedriver.dashboard.model.InstanceView;
import io.freedriver.dashboard.model.McpDebugResponse;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Path;

@ApplicationScoped
@Path("/api/mcp")
public class McpOpsResource implements McpOpsApi {

    @Inject
    ApplianceService appliances;

    @Inject
    MqttLiveClient mqtt;

    @Inject
    SecurityIdentity identity;

    @Override
    public ApplianceMapResponse appliances() {
        return appliances.currentMap();
    }

    @Override
    public HomeStatusResponse homeStatus() {
        ApplianceMapResponse map = appliances.currentMap();
        boolean stale = false;
        String lastUpdated = null;
        if (map.instances() != null) {
            for (InstanceView instance : map.instances()) {
                stale = stale || instance.stale();
                if (instance.lastUpdated() != null
                        && (lastUpdated == null || instance.lastUpdated().compareTo(lastUpdated) > 0)) {
                    lastUpdated = instance.lastUpdated();
                }
            }
        }
        return new HomeStatusResponse(mqtt.brokerConnected(), lastUpdated, stale);
    }

    @Override
    public McpDebugResponse debug() {
        if (!identity.hasRole("mcp-debug")) {
            throw new ForbiddenException();
        }
        HomeStatusResponse status = homeStatus();
        String category = !status.brokerConnected() ? "broker-down" : status.stale() ? "stale" : "none";
        return new McpDebugResponse("UP", category);
    }
}
