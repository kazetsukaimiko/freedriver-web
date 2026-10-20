package io.freedriver.app.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BffSessionLockTest {

    @Test
    void bff_is_web_app_cookie_session_and_off_by_default() throws IOException {
        String properties = Files.readString(Path.of("src/main/resources/auth-policy.properties"));
        String application = Files.readString(Path.of("src/main/resources/application.properties"));
        String loader = Files.readString(Path.of(
                "src/main/resources/META-INF/services/org.eclipse.microprofile.config.spi.ConfigSource"));
        assertTrue(loader.contains("io.freedriver.app.security.AuthPolicyConfigSource"));
        assertFalse(application.contains("quarkus.config.locations"));
        assertTrue(application.contains("quarkus.quinoa.ignored-path-prefixes=/api,/q,/login,/logout"));
        assertFalse(application.contains("quarkus.oidc."));
        assertFalse(application.contains("quarkus.http.auth.permission."));
        assertFalse(application.contains("freedriver.appliances.rate-limit."));
        assertTrue(properties.contains("quarkus.oidc.application-type=web-app"));
        assertTrue(properties.contains("quarkus.oidc.authentication.cookie-same-site=lax"));
        assertTrue(properties.contains("quarkus.oidc.authentication.cookie-force-secure=true"));
        assertFalse(properties.contains("quarkus.oidc.authentication.cookie-http-only"));
        assertTrue(properties.contains("quarkus.oidc.authentication.java-script-auto-redirect=false"));
        assertTrue(properties.contains("quarkus.http.auth.permission.appliances.policy=authenticated"));
        assertTrue(properties.contains("quarkus.http.auth.permission.changelog.paths=/api/changelog,/api/changelog/*"));
        assertTrue(properties.contains("quarkus.http.auth.permission.changelog.policy=authenticated"));
        assertTrue(properties.contains("quarkus.http.auth.permission.login.paths=/login"));
        assertTrue(properties.contains("quarkus.http.auth.permission.login.policy=authenticated"));
        assertTrue(properties.contains("quarkus.oidc.logout.path=/logout"));
        assertTrue(properties.contains("quarkus.oidc.logout.post-logout-path=/"));
        assertTrue(properties.contains("quarkus.http.auth.permission.logout.paths=/logout"));
        assertTrue(properties.contains("quarkus.http.auth.permission.logout.policy=authenticated"));
        assertTrue(properties.contains("quarkus.oidc.enabled=false"));
        assertFalse(properties.matches("(?s).*\\nquarkus\\.oidc\\.enabled=true.*"));
        assertTrue(properties.contains("quarkus.oidc.auth-server-url=https://auth.freedriver.io/realms/freedriver"));
        assertTrue(properties.contains("quarkus.oidc.client-id=freedriver-api"));
        assertTrue(properties.contains("quarkus.oidc.credentials.secret=${QUARKUS_OIDC_CREDENTIALS_SECRET:}"));
        assertTrue(properties.contains("\n%prod.quarkus.oidc.enabled=true\n"));
        assertTrue(properties.contains("\n%prod.quarkus.oidc.credentials.secret=${QUARKUS_OIDC_CREDENTIALS_SECRET}\n"));
        assertTrue(properties.contains("\n%prod.quarkus.oidc.authentication.force-redirect-https-scheme=true\n"));
        assertFalse(properties.contains("quarkus.oidc.credentials.secret=secret"));
    }

    @Test
    void post_logout_redirect_is_one_exact_address() throws IOException {
        String script = Files.readString(Path.of("../../scripts/provision-keycloak-freedriver.sh"));
        assertTrue(script.contains("POST_LOGOUT_REDIRECT_URI='https://app.freedriver.io/'"));
        assertFalse(script.contains("POST_LOGOUT_REDIRECT_URI='https://app.freedriver.io/*'"));
        assertFalse(script.contains("post.logout.redirect.uris\"=*"));
        assertFalse(script.contains("post.logout.redirect.uris\"=+"));
        int uses = 0;
        int from = 0;
        while (true) {
            int at = script.indexOf("POST_LOGOUT_REDIRECT_URI", from);
            if (at < 0) {
                break;
            }
            uses++;
            from = at + "POST_LOGOUT_REDIRECT_URI".length();
        }
        assertTrue(uses >= 3, "definition plus create and update, was " + uses);
    }

    @Test
    void confidential_secret_is_not_in_the_spa() throws IOException {
        Path webui = Path.of("src/main/webui");
        assertTrue(Files.isDirectory(webui), "webui");
        try (Stream<Path> walk = Files.walk(webui)) {
            walk.filter(path -> {
                String name = path.getFileName().toString();
                return name.endsWith(".ts")
                        || name.endsWith(".tsx")
                        || name.endsWith(".js")
                        || name.endsWith(".json")
                        || name.endsWith(".html");
            }).forEach(path -> {
                if (path.toString().contains("node_modules")) {
                    return;
                }
                try {
                    String body = Files.readString(path);
                    assertFalse(body.contains("QUARKUS_OIDC_CREDENTIALS_SECRET"), path.toString());
                    assertFalse(body.contains("keycloak-freedriver-api.secret"), path.toString());
                    assertFalse(body.contains("client-secret"), path.toString());
                    assertFalse(body.contains("credentials.secret"), path.toString());
                } catch (IOException e) {
                    throw new IllegalStateException(path.toString(), e);
                }
            });
        }
    }
}
