package io.freedriver.app.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsrfCookieSecureLockTest {

    @Test
    void secure_is_a_prod_profile_flag() throws IOException {
        String properties = Files.readString(Path.of("src/main/resources/application.properties"));
        String auth = Files.readString(Path.of("src/main/resources/auth-policy.properties"));
        assertTrue(properties.contains("\nfreedriver.csrf.cookie-secure=false\n"));
        assertTrue(properties.contains("\n%dev.freedriver.csrf.cookie-secure=false\n"));
        assertTrue(properties.contains("\n%prod.freedriver.csrf.cookie-secure=true\n"));
        assertFalse(properties.contains("%dev.freedriver.csrf.cookie-secure=true"));
        assertFalse(properties.contains("%test.freedriver.csrf.cookie-secure=true"));
        assertFalse(properties.contains("proxy-address-forwarding"));
        assertTrue(auth.contains("quarkus.oidc.authentication.cookie-force-secure=true"));
        assertFalse(auth.contains("quarkus.oidc.authentication.cookie-force-secure=false"));
    }

    @Test
    void filter_does_not_read_scheme_or_forwarded_headers() throws IOException {
        String body = Files.readString(Path.of("src/main/java/io/freedriver/app/security/CsrfFilter.java"));
        assertFalse(body.contains("getScheme"));
        assertFalse(body.contains("X-Forwarded-Proto"));
        assertFalse(body.contains("Forwarded"));
        assertFalse(body.contains("proxy-address-forwarding"));
    }
}
