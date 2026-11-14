package io.freedriver.app.security;

import java.util.HashMap;
import java.util.Map;

/** {@link PortalOidc} plus the {@code internal} service tenant on the same wiremock realm. */
public class InternalOidc extends PortalOidc {

    @Override
    public Map<String, String> start() {
        Map<String, String> conf = new HashMap<>(super.start());
        conf.put("quarkus.oidc.internal.auth-server-url", conf.get("quarkus.oidc.auth-server-url"));
        conf.put("quarkus.oidc.internal.token.issuer", "https://server.example.com");
        return conf;
    }
}
