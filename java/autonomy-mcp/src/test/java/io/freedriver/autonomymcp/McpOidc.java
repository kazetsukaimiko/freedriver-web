package io.freedriver.autonomymcp;

import io.quarkus.test.oidc.server.OidcWiremockTestResource;

import java.util.HashMap;
import java.util.Map;

/** Wiremock OIDC for the autonomy MCP bearer path. */
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
        conf.put("quarkus.oidc.application-type", "service");
        conf.put("quarkus.oidc.client-id", McpProtectedResource.CLIENT_ID);
        conf.put("quarkus.oidc.roles.source", "accesstoken");
        conf.put("quarkus.oidc.token.issuer", "https://server.example.com");
        conf.put("quarkus.oidc.token.audience", McpProtectedResource.RESOURCE);
        conf.put("quarkus.oidc.token.required-claims.azp", McpProtectedResource.CLIENT_ID);
        conf.put("quarkus.oidc.token.subject-required", "true");
        return conf;
    }
}
