package io.freedriver.dashboard.api;

import io.freedriver.dashboard.model.McpProbeResponse;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Path that accepts the Grok MCP bearer. JSON-RPC tools are #111.
 */
@Path("/autonomy/mcp")
@RolesAllowed("mcp-user")
@Produces(MediaType.APPLICATION_JSON)
public interface McpApi {

    @GET
    McpProbeResponse probeGet();

    @POST
    McpProbeResponse probePost();
}
