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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Dev profile on plain HTTP. Set-Cookie omits Secure on localhost and on 127.0.0.1. */
@QuarkusTest
@TestProfile(CsrfCookieDevTest.DevHttp.class)
class CsrfCookieDevTest {

    @TestHTTPResource
    URL base;

    @Inject
    @ConfigProperty(name = "freedriver.csrf.cookie-secure")
    boolean cookieSecure;

    @Test
    @TestSecurity(user = "dev", roles = {"dashboard"})
    void dev_localhost_omits_secure() {
        assertFalse(cookieSecure);
        SmallRyeConfig config = ConfigProvider.getConfig().unwrap(SmallRyeConfig.class);
        assertTrue(config.getProfiles().contains("dev"), config.getProfiles().toString());
        assertEquals("http", base.getProtocol(), base.toString());
        Response response = given()
                .header("X-Forwarded-Proto", "https")
                .header("Forwarded", "proto=https")
                .when()
                .get("http://localhost:" + base.getPort() + "/api/appliances");
        CsrfSetCookieChecks.assertMapCookie(response, false);
    }

    @Test
    @TestSecurity(user = "dev", roles = {"dashboard"})
    void dev_loopback_omits_secure() {
        assertEquals("http", base.getProtocol(), base.toString());
        Response response = given()
                .header("X-Forwarded-Proto", "https")
                .header("Forwarded", "proto=https")
                .when()
                .get("http://127.0.0.1:" + base.getPort() + "/api/appliances");
        CsrfSetCookieChecks.assertMapCookie(response, false);
    }

    public static class DevHttp implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "dev";
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.http.test-port", "0");
        }
    }
}
