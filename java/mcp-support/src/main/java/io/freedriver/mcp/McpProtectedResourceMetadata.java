package io.freedriver.mcp;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * OAuth protected-resource metadata (RFC 9728) for this service's MCP endpoint, at
 * {@code freedriver.mcp.metadata-path}. It is the only anonymous response besides {@code /q/*};
 * {@link McpMetadataPolicy} permits it.
 */
@ApplicationScoped
public class McpProtectedResourceMetadata {

    private final McpEndpoint endpoint;

    @Inject
    public McpProtectedResourceMetadata(McpEndpoint endpoint) {
        this.endpoint = endpoint;
    }

    void route(@Observes Router router) {
        router.get(endpoint.metadataPath()).handler(context -> context.response()
                .putHeader("Content-Type", "application/json")
                .end(document().encode()));
    }

    JsonObject document() {
        return new JsonObject()
                .put("resource", endpoint.resource())
                .put("authorization_servers", new JsonArray(endpoint.authorizationServers()))
                .put("bearer_methods_supported", new JsonArray().add("header"));
    }
}
