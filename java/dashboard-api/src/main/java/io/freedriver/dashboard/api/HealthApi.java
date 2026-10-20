package io.freedriver.dashboard.api;

import io.freedriver.dashboard.model.HealthResponse;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/health")
public interface HealthApi {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    HealthResponse health();
}
