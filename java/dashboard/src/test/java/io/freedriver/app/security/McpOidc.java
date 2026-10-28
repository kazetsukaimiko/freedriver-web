package io.freedriver.app.security;

import io.quarkus.test.oidc.server.OidcWiremockTestResource;

import java.util.HashMap;
import java.util.Map;

/**
 * Wiremock OIDC for the MCP bearer path. Config applies only to the annotated class.
 */
public class McpOidc extends OidcWiremockTestResource {

    @Override
    public Map<String, String> start() {
        Map<String, String> conf = new HashMap<>(super.start());
        String keycloak = conf.get("keycloak.url");
        if (keycloak == null || keycloak.isBlank()) {
            throw new IllegalStateException("keycloak.url");
        }
        String realm = keycloak + "/realms/quarkus";
        conf.put("quarkus.oidc.enabled", "true");
        conf.put("quarkus.oidc.auth-server-url", realm);
        conf.put("quarkus.oidc.client-id", "freedriver-api");
        conf.put("quarkus.oidc.credentials.secret", "secret");
        conf.put("quarkus.oidc.authentication.cookie-force-secure", "false");
        conf.put("quarkus.oidc.mcp.auth-server-url", realm);
        conf.put("quarkus.oidc.mcp.application-type", "service");
        conf.put("quarkus.oidc.mcp.client-id", McpPaths.CLIENT_ID);
        conf.put("quarkus.oidc.mcp.roles.source", "accesstoken");
        conf.put("quarkus.oidc.mcp.token.issuer", "https://server.example.com");
        conf.put("quarkus.oidc.mcp.token.audience", McpPaths.AUDIENCE);
        conf.put("quarkus.oidc.mcp.token.required-claims.azp", McpPaths.CLIENT_ID);
        conf.put("quarkus.oidc.mcp.token.subject-required", "true");
        conf.put("quarkus.oidc.mcp.tenant-paths", "/autonomy/mcp,/autonomy/mcp/*");
        conf.put("quarkus.oidc.mcp.tenant-enabled", "true");
        return conf;
    }
}
