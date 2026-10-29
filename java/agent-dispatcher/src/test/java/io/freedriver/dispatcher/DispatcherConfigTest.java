package io.freedriver.dispatcher;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatcherConfigTest {

    @Test
    void oidc_is_prod_only_with_issuer_and_azp_locks() throws IOException {
        String properties = Files.readString(Path.of("src/main/resources/application.properties"));
        assertTrue(properties.contains("\nquarkus.oidc.enabled=false\n"));
        assertTrue(properties.contains("\n%prod.quarkus.oidc.enabled=true\n"));
        assertTrue(properties.contains("\n%dev.quarkus.oidc.enabled=false\n"));
        assertTrue(properties.contains("\n%test.quarkus.oidc.enabled=false\n"));
        assertFalse(unprofiledEquals(properties, "quarkus.oidc.enabled", "true"));
        assertTrue(properties.contains("quarkus.oidc.application-type=service"));
        assertTrue(properties.contains("quarkus.oidc.auth-server-url=https://auth.freedriver.io/realms/freedriver"));
        assertTrue(properties.contains("quarkus.oidc.token.required-claims.azp=freedriver-dispatcher"));
        assertTrue(properties.contains("quarkus.tls.trust-all=false"));
        assertFalse(properties.contains("quarkus.oidc.tls.verification=none"));
        assertFalse(properties.contains("quarkus.tls.trust-all=true"));
        assertTrue(properties.contains("quarkus.oidc.token.allow-jwt-introspection=false"));
        assertTrue(properties.contains("quarkus.oidc.token.allow-opaque-token-introspection=false"));
        assertTrue(properties.contains("quarkus.oidc.token.require-jwt-introspection-only=false"));
        assertTrue(properties.contains("quarkus.oidc.credentials.secret=${FREEDRIVER_DISPATCHER_OIDC_SECRET:}"));
        assertFalse(hasPropertyKey(properties, "quarkus.oidc.token.audience"));
        assertFalse(hasPropertyKey(properties, "quarkus.oidc.token-path"));
        assertFalse(hasPropertyKey(properties, "quarkus.oidc.introspection-path"));
        assertFalse(properties.contains("quarkus.oidc.token.issuer=any"));
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
        assertFalse(properties.contains("paths=/dispatch/mcp"));
        assertFalse(properties.contains("paths=/api/dispatcher/*"));
        assertFalse(properties.contains("paths=/api/device"));

        String caddy = Files.readString(Path.of("../../Caddyfile"));
        assertTrue(caddy.contains(
                "@dispatcher path /mcp /mcp/* /dispatch/mcp /dispatch/mcp/* /api/dispatcher /api/dispatcher/* /api/device /api/device/*"));
        assertFalse(caddy.contains("oauth-protected-resource"));
        assertFalse(caddy.contains(".well-known"));
        assertFalse(caddy.contains("/autonomy/mcp"));

        String compose = Files.readString(Path.of("../../docker-compose.yml"));
        assertTrue(compose.contains("QUARKUS_OIDC_AUTH_SERVER_URL: https://auth.freedriver.io/realms/freedriver"));
        assertTrue(compose.contains("QUARKUS_OIDC_CREDENTIALS_SECRET: ${FREEDRIVER_DISPATCHER_OIDC_SECRET:-}"));
        assertFalse(compose.contains("QUARKUS_OIDC_ENABLED"));
        assertFalse(compose.contains("QUARKUS_OIDC_AUTH_SERVER_URL: http://keycloak:8080"));
        assertFalse(compose.contains("QUARKUS_OIDC_AUTH_SERVER_URL: http://127.0.0.1:8081"));
    }

    private static boolean unprofiledEquals(String properties, String key, String value) {
        String needle = key + "=" + value;
        for (String line : properties.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || trimmed.startsWith("%")) {
                continue;
            }
            if (trimmed.equals(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPropertyKey(String properties, String key) {
        String prefix = key + "=";
        for (String line : properties.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith("%")) {
                int dot = trimmed.indexOf('.');
                if (dot < 0) {
                    continue;
                }
                trimmed = trimmed.substring(dot + 1);
            }
            if (trimmed.startsWith(prefix) || trimmed.equals(key)) {
                return true;
            }
        }
        return false;
    }
}
