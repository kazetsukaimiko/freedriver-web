package io.freedriver.app.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

@Path("/api/health")
public class HealthResource {

    @ConfigProperty(name = "quarkus.application.version")
    Optional<String> applicationVersion;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HealthResponse(String status, String build) {
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public HealthResponse health() {
        return new HealthResponse(
                "UP", PublishedBuild.resolve(HealthResource.class, applicationVersion.orElse(null)));
    }
}
