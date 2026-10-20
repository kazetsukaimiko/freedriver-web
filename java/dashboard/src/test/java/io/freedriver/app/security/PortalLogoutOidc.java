package io.freedriver.app.security;

import io.quarkus.test.oidc.server.OidcWiremockTestResource;

import java.util.HashMap;
import java.util.Map;

/**
 * Wiremock OIDC for the portal logout test. Config applies only to the annotated class.
 */
public class PortalLogoutOidc extends OidcWiremockTestResource {

    @Override
    public Map<String, String> start() {
        Map<String, String> conf = new HashMap<>(super.start());
        String keycloak = conf.get("keycloak.url");
        if (keycloak == null || keycloak.isBlank()) {
            throw new IllegalStateException("keycloak.url");
        }
        conf.put("quarkus.oidc.enabled", "true");
        conf.put("quarkus.oidc.auth-server-url", keycloak + "/realms/quarkus");
        conf.put("quarkus.oidc.client-id", "freedriver-api");
        conf.put("quarkus.oidc.credentials.secret", "secret");
        conf.put("quarkus.oidc.authentication.cookie-force-secure", "false");
        return conf;
    }
}
