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
        assertFalse(properties.contains("paths=/mcp"));
    }
}
