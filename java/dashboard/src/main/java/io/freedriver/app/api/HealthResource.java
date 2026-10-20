package io.freedriver.app.api;

import io.freedriver.dashboard.model.HealthResponse;
import io.freedriver.dashboard.api.HealthApi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Path;

@ApplicationScoped
@Path("/api/health")
public class HealthResource implements HealthApi {

    @Override
    public HealthResponse health() {
        return new HealthResponse("UP");
    }
}
