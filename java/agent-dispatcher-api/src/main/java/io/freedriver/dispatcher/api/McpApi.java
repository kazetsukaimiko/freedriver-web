package io.freedriver.dispatcher.api;

import io.freedriver.dispatcher.model.JsonRpcResponse;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/{mcpPath:mcp|dispatch/mcp}")
public interface McpApi {

    String SESSION_HEADER = "MCP-Session-Id";

    String PROTOCOL_HEADER = "MCP-Protocol-Version";

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed(Actors.DISPATCHER)
    JsonRpcResponse post(
            @HeaderParam(SESSION_HEADER) String sessionId,
            @HeaderParam(PROTOCOL_HEADER) String protocolVersion,
            String body);

    @GET
    @RolesAllowed(Actors.DISPATCHER)
    void get(@HeaderParam(SESSION_HEADER) String sessionId);

    @DELETE
    @RolesAllowed(Actors.DISPATCHER)
    void delete(@HeaderParam(SESSION_HEADER) String sessionId);
}
