package io.freedriver.dispatcher;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatcherConfigTest {

    @Test
    void oidc_stays_off_and_timeouts_match_the_contract() throws IOException {
        String properties = Files.readString(Path.of("src/main/resources/application.properties"));
        assertTrue(properties.contains("quarkus.oidc.enabled=false"));
        assertFalse(properties.contains("quarkus.oidc.enabled=true"));
        assertTrue(properties.contains("quarkus.oidc.application-type=service"));
        assertTrue(properties.contains("quarkus.oidc.auth-server-url=https://auth.freedriver.io/realms/freedriver"));
        assertTrue(properties.contains("freedriver.dispatcher.submit-timeout=75s"));
        assertTrue(properties.contains("freedriver.dispatcher.poll-timeout=25s"));
        assertTrue(properties.contains("%test.freedriver.dispatcher.submit-timeout=300ms"));
        assertTrue(properties.contains("quarkus.http.auth.permission.authenticated.paths=/*"));
        assertTrue(properties.contains("quarkus.http.auth.permission.public.paths=/q/*"));
        assertTrue(properties.contains(
                "quarkus.http.auth.permission.metadata.paths=/api/dispatcher/oauth-protected-resource"));
        assertTrue(properties.contains("quarkus.http.auth.permission.metadata.policy=mcp-metadata"));
        assertTrue(properties.contains("quarkus.http.auth.permission.metadata.methods=GET"));
        assertFalse(properties.contains("paths=/mcp"));
        assertFalse(properties.contains("paths=/mcp/*"));
        assertFalse(properties.contains("paths=/api/dispatcher/*"));
        assertFalse(properties.contains("paths=/api/device"));

        String caddy = Files.readString(Path.of("../../Caddyfile"));
        assertTrue(caddy.contains(
                "@dispatcher path /mcp /mcp/* /api/dispatcher /api/dispatcher/* /api/device /api/device/*"));
        assertFalse(caddy.contains("oauth-protected-resource"));
        assertFalse(caddy.contains(".well-known"));
    }
}
