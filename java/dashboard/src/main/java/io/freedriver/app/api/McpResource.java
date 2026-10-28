package io.freedriver.app.api;

import io.freedriver.dashboard.api.McpApi;
import io.freedriver.dashboard.model.McpProbeResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Path;

@ApplicationScoped
@Path("/autonomy/mcp")
public class McpResource implements McpApi {

    @Override
    public McpProbeResponse probeGet() {
        return new McpProbeResponse("ok");
    }

    @Override
    public McpProbeResponse probePost() {
        return new McpProbeResponse("ok");
    }
}
