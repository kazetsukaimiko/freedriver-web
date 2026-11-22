package io.freedriver.dashboard.api;

import io.freedriver.dashboard.model.ApplianceMapResponse;
import io.freedriver.dashboard.model.HomeStatusResponse;
import io.freedriver.dashboard.model.McpDebugResponse;
import io.freedriver.dashboard.model.ScheduledSwitchRequest;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/mcp")
@Produces(MediaType.APPLICATION_JSON)
public interface McpOpsApi {

    @GET
    @Path("/appliances")
    @RolesAllowed("mcp-user")
    ApplianceMapResponse appliances();

    @GET
    @Path("/home-status")
    @RolesAllowed("mcp-user")
    HomeStatusResponse homeStatus();

    @GET
    @Path("/debug")
    @RolesAllowed("mcp-user")
    McpDebugResponse debug();

    /**
     * Stores one delayed switch. 202 means the row is stored. A spent {@code appliances.schedules}
     * window and a full pending cap are both an empty 429. The cap response carries
     * {@link PendingCapHeader#NAME} {@link PendingCapHeader#FULL}; the spent window leaves that
     * header off. Stored rows still fire.
     */
    @POST
    @Path("/scheduled-switches")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({"dashboard", "portal-admin"})
    @RateLimited(RateLimitBucket.APPLIANCES_SCHEDULES)
    Response schedule(@Valid @NotNull ScheduledSwitchRequest body);
}
