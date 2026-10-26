package io.freedriver.app.api;

import io.freedriver.dashboard.model.BuildResponse;
import io.freedriver.dashboard.api.BuildApi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Public read-only stamp for the UX badge (#49).
 * Main deploys bake MAJOR.MINOR.PATCH into {@code quarkus.application.version}.
 * That string is the git version tag.
 */
@ApplicationScoped
@Path("/api/build")
public class BuildResource implements BuildApi {

    @Inject
    @ConfigProperty(name = "quarkus.application.version")
    String version;

    @Override
    public BuildResponse build() {
        return new BuildResponse(version);
    }
}
