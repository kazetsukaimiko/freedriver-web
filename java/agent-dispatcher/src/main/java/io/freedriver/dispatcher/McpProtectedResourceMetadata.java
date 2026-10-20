package io.freedriver.dispatcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The only unauthenticated dispatcher response. It is the metadata document and nothing else.
 */
@ApplicationScoped
@Path(McpProtectedResource.PATH)
public class McpProtectedResourceMetadata {

    private final ObjectMapper mapper;

    @Inject
    public McpProtectedResourceMetadata(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @GET
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    public ObjectNode get() {
        ObjectNode document = mapper.createObjectNode();
        document.put("resource", McpProtectedResource.RESOURCE);
        ArrayNode servers = document.putArray("authorization_servers");
        servers.add(McpProtectedResource.AUTHORIZATION_SERVER);
        ArrayNode methods = document.putArray("bearer_methods_supported");
        methods.add("header");
        return document;
    }
}
