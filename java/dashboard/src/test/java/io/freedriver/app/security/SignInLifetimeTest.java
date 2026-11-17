package io.freedriver.app.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One number sets how long a sign-in may take (#552): ACCESS_CODE_LIFESPAN_LOGIN in the provision
 * script is Keycloak's sign-in session, which the login theme reads from the realm, and the
 * dashboard's OIDC state cookie lives exactly as long.
 */
public class SignInLifetimeTest {

    public static long provisionedSeconds() throws IOException {
        String script = Files.readString(Path.of("../../scripts/provision-keycloak-freedriver.sh"));
        Matcher m = Pattern.compile("(?m)^ACCESS_CODE_LIFESPAN_LOGIN=(\\d+)$").matcher(script);
        assertTrue(m.find(), "ACCESS_CODE_LIFESPAN_LOGIN in the provision script");
        return Long.parseLong(m.group(1));
    }

    static Duration stateCookieAge() throws IOException {
        String policy = Files.readString(Path.of("src/main/resources/auth-policy.properties"));
        Matcher m = Pattern.compile("(?m)^quarkus\\.oidc\\.authentication\\.state-cookie-age=(\\d+)s$").matcher(policy);
        assertTrue(m.find(), "state-cookie-age in seconds in auth-policy.properties");
        return Duration.ofSeconds(Long.parseLong(m.group(1)));
    }

    @Test
    void the_state_cookie_lives_as_long_as_keycloaks_sign_in_session() throws IOException {
        assertEquals(Duration.ofSeconds(provisionedSeconds()), stateCookieAge());
    }

    @Test
    void the_sign_in_session_outlasts_the_old_five_minute_state_cookie() throws IOException {
        assertTrue(stateCookieAge().compareTo(Duration.ofMinutes(5)) > 0, stateCookieAge().toString());
    }
}
