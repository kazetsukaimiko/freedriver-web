package io.freedriver.dispatcher;

import io.quarkus.test.oidc.server.OidcWiremockTestResource;

import java.util.HashMap;
import java.util.Map;

/** Wiremock OIDC for the host API with OIDC on. */
public class HostOidc extends OidcWiremockTestResource {

    @Override
    public Map<String, String> start() {
        Map<String, String> conf = new HashMap<>(super.start());
        String keycloak = conf.get("keycloak.url");
        if (keycloak == null || keycloak.isBlank()) {
            throw new IllegalStateException("keycloak.url");
        }
        conf.put("quarkus.oidc.auth-server-url", keycloak + "/realms/quarkus");
        return conf;
    }
}
