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

    @POST
    @Path("/scheduled-switches")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({"dashboard", "portal-admin"})
    Response schedule(@Valid @NotNull ScheduledSwitchRequest body);
}
