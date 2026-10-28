package io.freedriver.app.api;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

/**
 * Path that accepts the Grok MCP bearer. JSON-RPC tools are #111.
 */
@Path("/autonomy/mcp")
@RolesAllowed("mcp-user")
public class McpResource {

    @GET
    public Response probeGet() {
        return Response.noContent().build();
    }

    @POST
    public Response probePost() {
        return Response.noContent().build();
    }
}
