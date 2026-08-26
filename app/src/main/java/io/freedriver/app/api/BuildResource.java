package io.freedriver.app.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Public read-only stamp for the UX badge (#49).
 * Main deploys bake YEAR-MONTH_rBUILD_NUM into {@code quarkus.application.version}.
 */
@Path("/api/build")
public class BuildResource {

    @ConfigProperty(name = "quarkus.application.version")
    String version;

    public record BuildResponse(String build) {
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public BuildResponse build() {
        return new BuildResponse(version);
    }
}
