package io.freedriver.app.security;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import io.smallrye.config.SmallRyeConfig;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prod profile over the test connector, which is plain HTTP.
 * Set-Cookie stays Secure. The overrides reach a 200 map without a live IdP
 * and do not set the CSRF flag.
 */
@QuarkusTest
@TestProfile(CsrfCookieProdTest.ProdHttp.class)
class CsrfCookieProdTest {

    @TestHTTPResource
    URL base;

    @Inject
    @ConfigProperty(name = "freedriver.csrf.cookie-secure")
    boolean cookieSecure;

    @Test
    @TestSecurity(user = "ada", roles = {"dashboard"})
    void prod_plain_http_sets_secure_httponly_lax_cookie() {
        assertEquals("http", base.getProtocol(), base.toString());
        assertTrue(cookieSecure);
        SmallRyeConfig config = ConfigProvider.getConfig().unwrap(SmallRyeConfig.class);
        assertTrue(config.getProfiles().contains("prod"), config.getProfiles().toString());
        assertEquals("true", config.getConfigValue("freedriver.csrf.cookie-secure").getValue());

        String url = "http://127.0.0.1:" + base.getPort() + "/api/appliances";
        assertTrue(url.startsWith("http://"), url);
        Response response = given()
                .header("X-Forwarded-Proto", "http")
                .header("Forwarded", "proto=http")
                .when()
                .get(url);
        CsrfSetCookieChecks.assertMapCookie(response, true);
    }

    public static class ProdHttp implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "prod";
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.oidc.enabled", "false",
                    "quarkus.oidc.credentials.secret", "unused-by-this-test",
                    "freedriver.invite.client-secret", "unused-by-this-test",
                    "freedriver.appliances.enabled", "true",
                    "freedriver.appliances.live-commands", "false",
                    "freedriver.appliances.mock", "true",
                    "freedriver.appliances.mock-refresh", "false",
                    "quarkus.http.test-port", "0");
        }
    }
}
