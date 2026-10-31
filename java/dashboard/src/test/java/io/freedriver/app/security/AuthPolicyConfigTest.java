package io.freedriver.app.security;

import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import io.freedriver.app.appliances.AppliancesConfig;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Auth permissions and the command rate limit still load, from the code-owned file. */
@QuarkusTest
class AuthPolicyConfigTest {

    @Inject
    AppliancesConfig appliances;

    @Test
    void each_permission_and_the_rate_limit_match_the_moved_file() throws IOException {
        String auth = Files.readString(Path.of("src/main/resources/auth-policy.properties"));
        assertTrue(auth.contains("quarkus.http.auth.permission.public.paths=/*"));
        assertTrue(auth.contains("quarkus.http.auth.permission.public.policy=permit"));
        assertTrue(auth.contains("quarkus.http.auth.permission.appliances.paths=/api/appliances,/api/appliances/*"));
        assertTrue(auth.contains("quarkus.http.auth.permission.appliances.policy=authenticated"));
        assertTrue(auth.contains("quarkus.http.auth.permission.changelog.paths=/api/changelog,/api/changelog/*"));
        assertTrue(auth.contains("quarkus.http.auth.permission.changelog.policy=authenticated"));
        assertTrue(auth.contains("quarkus.http.auth.permission.login.paths=/login"));
        assertTrue(auth.contains("quarkus.http.auth.permission.login.policy=authenticated"));
        assertTrue(auth.contains("quarkus.http.auth.permission.logout.paths=/logout"));
        assertTrue(auth.contains("quarkus.http.auth.permission.logout.policy=authenticated"));
        assertTrue(auth.contains("quarkus.http.auth.permission.invites.paths=/api/invites,/api/invites/*"));
        assertTrue(auth.contains("quarkus.http.auth.permission.invites.policy=authenticated"));
        assertTrue(auth.contains("quarkus.http.auth.permission.invite-use.paths=/api/invites/use"));
        assertTrue(auth.contains("quarkus.http.auth.permission.invite-use.policy=permit"));
        assertTrue(auth.contains("quarkus.http.auth.permission.invite-redeem.paths=/api/invites/redeem,/api/invites/redeem/*"));
        assertTrue(auth.contains("quarkus.http.auth.permission.invite-redeem.policy=permit"));
        assertTrue(auth.contains("quarkus.http.auth.permission.phones.paths=/api/phones,/api/phones/*"));
        assertTrue(auth.contains("quarkus.http.auth.permission.phones.policy=authenticated"));
        assertTrue(auth.contains("quarkus.http.auth.permission.instances.paths=/api/instances,/api/instances/*"));
        assertTrue(auth.contains("quarkus.http.auth.permission.instances.policy=authenticated"));
        assertTrue(auth.contains("quarkus.http.auth.permission.mcp.paths=/autonomy/mcp,/autonomy/mcp/*"));
        assertTrue(auth.contains("quarkus.http.auth.permission.mcp.policy=authenticated"));
        assertTrue(auth.contains("quarkus.oidc.mcp.client-id=freedriver-grok-mcp"));
        assertTrue(auth.contains("quarkus.oidc.mcp.token.audience=https://app.freedriver.io/autonomy/mcp"));
        assertTrue(auth.contains("freedriver.instances.client-id=freedriver-instances"));
        assertTrue(auth.contains("freedriver.instances.client-secret=${FREEDRIVER_INSTANCES_CLIENT_SECRET:}"));
        assertTrue(auth.contains("freedriver.appliances.rate-limit.permits=30"));
        assertTrue(auth.contains("freedriver.appliances.rate-limit.window=60s"));
        assertTrue(auth.contains("%test.freedriver.appliances.rate-limit.permits=8"));
        assertTrue(auth.contains("%test.freedriver.appliances.rate-limit.window=60s"));

        var config = ConfigProvider.getConfig();
        assertEquals("/*", config.getValue("quarkus.http.auth.permission.public.paths", String.class));
        assertEquals("permit", config.getValue("quarkus.http.auth.permission.public.policy", String.class));
        assertEquals(
                "/api/appliances,/api/appliances/*",
                config.getValue("quarkus.http.auth.permission.appliances.paths", String.class));
        assertEquals("authenticated", config.getValue("quarkus.http.auth.permission.appliances.policy", String.class));
        assertEquals(
                "/api/changelog,/api/changelog/*",
                config.getValue("quarkus.http.auth.permission.changelog.paths", String.class));
        assertEquals("authenticated", config.getValue("quarkus.http.auth.permission.changelog.policy", String.class));
        assertEquals("/login", config.getValue("quarkus.http.auth.permission.login.paths", String.class));
        assertEquals("authenticated", config.getValue("quarkus.http.auth.permission.login.policy", String.class));
        assertEquals("/logout", config.getValue("quarkus.http.auth.permission.logout.paths", String.class));
        assertEquals("authenticated", config.getValue("quarkus.http.auth.permission.logout.policy", String.class));
        assertEquals(
                "/api/invites,/api/invites/*",
                config.getValue("quarkus.http.auth.permission.invites.paths", String.class));
        assertEquals("authenticated", config.getValue("quarkus.http.auth.permission.invites.policy", String.class));
        assertEquals("/api/invites/use", config.getValue("quarkus.http.auth.permission.invite-use.paths", String.class));
        assertEquals("permit", config.getValue("quarkus.http.auth.permission.invite-use.policy", String.class));
        assertEquals("/api/invites/redeem,/api/invites/redeem/*",
                config.getValue("quarkus.http.auth.permission.invite-redeem.paths", String.class));
        assertEquals("permit", config.getValue("quarkus.http.auth.permission.invite-redeem.policy", String.class));
        assertEquals(
                "/api/phones,/api/phones/*",
                config.getValue("quarkus.http.auth.permission.phones.paths", String.class));
        assertEquals("authenticated", config.getValue("quarkus.http.auth.permission.phones.policy", String.class));
        assertEquals(
                "/api/instances,/api/instances/*",
                config.getValue("quarkus.http.auth.permission.instances.paths", String.class));
        assertEquals("authenticated", config.getValue("quarkus.http.auth.permission.instances.policy", String.class));
        assertEquals(
                "/autonomy/mcp,/autonomy/mcp/*",
                config.getValue("quarkus.http.auth.permission.mcp.paths", String.class));
        assertEquals("authenticated", config.getValue("quarkus.http.auth.permission.mcp.policy", String.class));
        assertEquals("freedriver-grok-mcp", config.getValue("quarkus.oidc.mcp.client-id", String.class));
        assertEquals(
                "https://app.freedriver.io/autonomy/mcp",
                config.getValue("quarkus.oidc.mcp.token.audience", String.class));
        assertEquals("freedriver-instances", config.getValue("freedriver.instances.client-id", String.class));
        assertEquals(8, appliances.rateLimit().permits());
        assertEquals(Duration.ofSeconds(60), appliances.rateLimit().window());
        assertFalse(config.getValue("quarkus.oidc.enabled", Boolean.class));
    }
}
