package io.freedriver.dashboard.api;

import io.freedriver.dashboard.model.ApplianceMapResponse;
import io.freedriver.dashboard.model.HomeStatusResponse;
import io.freedriver.dashboard.model.McpDebugResponse;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

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
}
