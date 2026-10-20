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
        String owners = Files.readString(Path.of("../../.github/CODEOWNERS"));
        assertTrue(owners.contains("/java/dashboard/src/main/resources/auth-policy.properties @kazetsukaimiko"));
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
        assertEquals(8, appliances.rateLimit().permits());
        assertEquals(Duration.ofSeconds(60), appliances.rateLimit().window());
        assertFalse(config.getValue("quarkus.oidc.enabled", Boolean.class));
    }
}
