package io.freedriver.autonomymcp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpConfigTest {

    @Test
    void oidc_pins_issuer_and_audience_and_stays_off_by_default() throws IOException {
        String properties = Files.readString(Path.of("src/main/resources/application.properties"));
        assertTrue(properties.contains("\nquarkus.oidc.enabled=false\n"));
        assertTrue(properties.contains("\n%prod.quarkus.oidc.enabled=true\n"));
        assertTrue(properties.contains("\n%dev.quarkus.oidc.enabled=false\n"));
        assertTrue(properties.contains("\n%test.quarkus.oidc.enabled=false\n"));
        assertTrue(properties.contains("quarkus.oidc.application-type=service"));
        assertTrue(properties.contains("quarkus.oidc.auth-server-url=https://auth.freedriver.io/realms/freedriver"));
        assertTrue(properties.contains("quarkus.oidc.token.audience=https://app.freedriver.io/autonomy/mcp"));
        assertFalse(properties.contains("quarkus.oidc.token.required-claims.azp"));
        assertTrue(properties.contains("quarkus.tls.trust-all=false"));
        assertFalse(properties.contains("quarkus.tls.trust-all=true"));
        assertTrue(properties.contains("%prod.quarkus.redis.hosts=${FREEDRIVER_REDIS_URL:redis://redis:6379}"));
        assertTrue(properties.contains("freedriver.redis.url=${quarkus.redis.hosts:redis://redis:6379}"));
        assertFalse(properties.contains("quarkus.mongodb"));
        assertTrue(properties.contains("freedriver.mcp.enabled=false"));
        assertTrue(properties.contains("freedriver.mcp.dashboard-url=http://dashboard:8080"));
        assertFalse(properties.contains("freedriver.appliances.live-commands=true"));
        assertFalse(properties.contains("/appliances/mcp"));
        assertFalse(properties.contains("paths=/mcp"));
        assertFalse(properties.contains("paths=/dispatch/mcp"));

        String caddy = Files.readString(Path.of("../../Caddyfile"));
        assertTrue(caddy.contains("@autonomy path /autonomy/mcp /autonomy/mcp/* /api/autonomy /api/autonomy/*"));
        assertTrue(caddy.contains("reverse_proxy autonomy-mcp:8080"));
        assertTrue(caddy.contains("header_up -X-Freedriver-Internal"));
        assertTrue(caddy.contains("header_up -X-Freedriver-Subject"));
        assertTrue(caddy.contains("header_up -X-Freedriver-Roles"));
        assertFalse(caddy.contains("/appliances/mcp"));
        assertFalse(caddy.contains("@dispatcher path /autonomy/mcp"));

        String compose = Files.readString(Path.of("../../docker-compose.yml"));
        assertTrue(compose.contains("autonomy-mcp:"));
        assertTrue(compose.contains("FREEDRIVER_MCP_DASHBOARD_URL: http://dashboard:8080"));
        assertTrue(compose.contains("FREEDRIVER_MCP_ENABLED: ${FREEDRIVER_AUTONOMY_MCP_ENABLED:-false}"));
        assertFalse(compose.contains("FREEDRIVER_APPLIANCES_LIVE_COMMANDS: \"true\"\n    # autonomy"));
    }
}
