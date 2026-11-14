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
        assertFalse(properties.contains("quarkus.oidc.token.required-claims.azp"));
        assertTrue(properties.contains("freedriver.dispatcher.client.dispatcher=freedriver-dispatcher"));
        assertTrue(properties.contains("freedriver.dispatcher.client.device=freedriver-openbot"));
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
        assertTrue(properties.contains(
                "freedriver.dispatcher.openbot-webhook-secret=${FREEDRIVER_DISPATCHER_OPENBOT_WEBHOOK_SECRET:}"));
        assertFalse(properties.contains("openbot-webhook-secret=test"));
        assertTrue(properties.contains("quarkus.mongodb.server-selection-timeout=2S"));
        assertTrue(properties.contains("quarkus.http.auth.permission.authenticated.paths=/*"));
        assertTrue(properties.contains("quarkus.http.auth.permission.public.paths=/q/*"));
        assertTrue(properties.contains("quarkus.smallrye-health.ui.enabled=false"));
        assertTrue(properties.contains(
                "quarkus.http.auth.permission.metadata.paths=/api/dispatcher/oauth-protected-resource"));
        assertTrue(properties.contains("quarkus.http.auth.permission.metadata.policy=mcp-metadata"));
        assertTrue(properties.contains("quarkus.http.auth.permission.metadata.methods=GET"));
        String permissions = properties.lines()
                .filter(line -> line.startsWith("quarkus.http.auth.permission."))
                .collect(java.util.stream.Collectors.joining("\n"));
        assertFalse(permissions.contains("paths=/mcp"));
        assertFalse(permissions.contains("paths=/mcp/*"));
        assertFalse(permissions.contains("paths=/dispatch/mcp"));
        assertFalse(permissions.contains("paths=/api/dispatcher/*"));
        assertFalse(permissions.contains("paths=/api/device"));

        String caddy = Files.readString(Path.of("../../Caddyfile"));
        assertTrue(caddy.contains(
                "@dispatcher path /mcp /mcp/* /dispatch/mcp /dispatch/mcp/* /api/dispatcher /api/dispatcher/*"));
        assertTrue(caddy.contains(
                "@autonomy path /autonomy/mcp /autonomy/mcp/* /api/autonomy /api/autonomy/*"));
        int autonomy = caddy.indexOf(
                "@autonomy path /autonomy/mcp /autonomy/mcp/* /api/autonomy /api/autonomy/*");
        int catchAll = caddy.indexOf("handle {\n\t\treverse_proxy {\n\t\t\tdynamic a dashboard 8080");
        assertTrue(autonomy >= 0 && catchAll > autonomy);
        assertTrue(caddy.substring(autonomy, catchAll).contains("dynamic a autonomy-mcp 8080"));
        assertTrue(caddy.contains("dynamic a agent-dispatcher 8080"));
        assertTrue(caddy.contains("lb_policy header Mcp-Session-Id"));
        assertFalse(caddy.contains("oauth-protected-resource"));
        assertFalse(caddy.contains(".well-known"));
        assertFalse(caddy.contains("@dispatcher path /autonomy/mcp"));

        String pom = Files.readString(Path.of("pom.xml"));
        assertTrue(pom.contains("quarkus-smallrye-health"));

        String compose = Files.readString(Path.of("../../docker-compose.yml"));
        assertTrue(compose.contains("QUARKUS_OIDC_AUTH_SERVER_URL: https://auth.freedriver.io/realms/freedriver"));
        assertTrue(compose.contains("QUARKUS_OIDC_CREDENTIALS_SECRET: ${FREEDRIVER_DISPATCHER_OIDC_SECRET:-}"));
        assertTrue(compose.contains(
                "FREEDRIVER_DISPATCHER_OPENBOT_WEBHOOK_SECRET: ${FREEDRIVER_DISPATCHER_OPENBOT_WEBHOOK_SECRET:-}"));
        assertTrue(compose.contains(
                "QUARKUS_MONGODB_CONNECTION_STRING: ${QUARKUS_MONGODB_CONNECTION_STRING:-mongodb://mongodb:27017/freedriver}"));
        assertTrue(compose.contains("replicas: ${DASHBOARD_REPLICAS:-1}"));
        assertTrue(compose.contains("replicas: ${DISPATCHER_REPLICAS:-1}"));
        assertTrue(compose.contains("replicas: ${AUTONOMY_MCP_REPLICAS:-1}"));
        assertTrue(compose.contains("/usr/local/bin/health-ready.sh"));
        assertFalse(compose.contains("QUARKUS_OIDC_ENABLED"));
        assertFalse(compose.contains("QUARKUS_OIDC_AUTH_SERVER_URL: http://keycloak:8080"));
        assertFalse(compose.contains("QUARKUS_OIDC_AUTH_SERVER_URL: http://127.0.0.1:8081"));

        String prometheus = Files.readString(Path.of("../../observability/prometheus/prometheus.yml"));
        assertTrue(prometheus.contains("names: [dashboard]"));
        assertTrue(prometheus.contains("names: [agent-dispatcher]"));
        assertTrue(prometheus.contains("names: [autonomy-mcp]"));
        assertTrue(prometheus.contains("dns_sd_configs:"));

        Path root = Path.of("../..");
        assertTrue(Files.readString(root.resolve("java/dashboard/src/main/docker/Dockerfile.compose"))
                .contains("health-ready.sh"));
        assertTrue(Files.readString(root.resolve("java/agent-dispatcher/src/main/docker/Dockerfile.compose"))
                .contains("health-ready.sh"));
        assertTrue(Files.readString(root.resolve("java/autonomy-mcp/src/main/docker/Dockerfile.compose"))
                .contains("health-ready.sh"));
    }

    @Test
    void client_filter_pairs_each_client_with_its_role() {
        assertTrue(AllowedClientFilter.allowed(false, false, "other", false, false, false, "freedriver-dispatcher", "freedriver-openbot", "freedriver-portal-jobs"));
        assertTrue(AllowedClientFilter.allowed(true, true, null, false, false, false, "freedriver-dispatcher", "freedriver-openbot", "freedriver-portal-jobs"));
        assertTrue(AllowedClientFilter.allowed(true, false, "freedriver-dispatcher", true, false, false, "freedriver-dispatcher", "freedriver-openbot", "freedriver-portal-jobs"));
        assertTrue(AllowedClientFilter.allowed(true, false, "freedriver-openbot", false, true, false, "freedriver-dispatcher", "freedriver-openbot", "freedriver-portal-jobs"));
        assertFalse(AllowedClientFilter.allowed(true, false, "freedriver-openbot", true, false, false, "freedriver-dispatcher", "freedriver-openbot", "freedriver-portal-jobs"));
        assertFalse(AllowedClientFilter.allowed(true, false, "freedriver-dispatcher", false, true, false, "freedriver-dispatcher", "freedriver-openbot", "freedriver-portal-jobs"));
        assertFalse(AllowedClientFilter.allowed(true, false, "shizuku", false, true, false, "freedriver-dispatcher", "freedriver-openbot", "freedriver-portal-jobs"));
        assertTrue(AllowedClientFilter.allowed(true, false, "freedriver-portal-jobs", false, false, true, "freedriver-dispatcher", "freedriver-openbot", "freedriver-portal-jobs"));
        assertFalse(AllowedClientFilter.allowed(true, false, "freedriver-portal-jobs", true, false, false, "freedriver-dispatcher", "freedriver-openbot", "freedriver-portal-jobs"));
        assertFalse(AllowedClientFilter.allowed(true, false, "freedriver-dispatcher", false, false, true, "freedriver-dispatcher", "freedriver-openbot", "freedriver-portal-jobs"));
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
