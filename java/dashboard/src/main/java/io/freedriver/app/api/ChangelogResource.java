package io.freedriver.app.api;

import io.freedriver.app.changelog.Changelog;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The changelog packaged in the jar, as plain text, for the read-only roles (#152).
 * 404 while the jar has no changelog file.
 */
@Path("/api/changelog")
public class ChangelogResource {

    @Inject
    Changelog changelog;

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @RolesAllowed({"portal-admin", "dashboard", "changelog"})
    public Response changelog() {
        return changelog.text()
                .map(Response::ok)
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND))
                .build();
    }
}
