package io.freedriver.app.api;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With OIDC sign-in on, a request without a session is answered by the app-wide OIDC web-app
 * mechanism: the dashboard's JavaScript requests get 499, and a browser opening the same path
 * gets the redirect to sign-in. Discovery is off so the test never calls the identity provider;
 * the challenge only needs the authorization endpoint.
 */
@QuarkusTest
@TestProfile(SignInRequiredTest.OidcOnProfile.class)
class SignInRequiredTest {

    static final String AUTHORIZATION_ENDPOINT =
            "https://auth.freedriver.io/realms/freedriver/protocol/openid-connect/auth";
    static final String INSTANCE_ID = "550e8400-e29b-41d4-a716-446655440000";

    public static class OidcOnProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.oidc.enabled", "true",
                    "quarkus.oidc.discovery-enabled", "false",
                    "quarkus.oidc.authorization-path", "protocol/openid-connect/auth",
                    "quarkus.oidc.token-path", "protocol/openid-connect/token",
                    "quarkus.http.test-port", "0");
        }
    }

    @Test
    void javascript_appliance_list_without_session_is_499() {
        given().header("X-Requested-With", "JavaScript")
                .redirects().follow(false)
                .when().get("/api/appliances")
                .then()
                .statusCode(499)
                .header("WWW-Authenticate", "OIDC")
                .header("Location", nullValue());
    }

    @Test
    void javascript_switch_command_without_session_is_499() {
        given().header("X-Requested-With", "JavaScript")
                .contentType(ContentType.JSON)
                .body("{\"on\":true}")
                .redirects().follow(false)
                .when().post("/api/appliances/" + INSTANCE_ID + "/hallway")
                .then()
                .statusCode(499)
                .header("Location", nullValue());
    }

    @Test
    void javascript_changelog_without_session_is_499() {
        given().header("X-Requested-With", "JavaScript")
                .redirects().follow(false)
                .when().get("/api/changelog")
                .then()
                .statusCode(499)
                .header("WWW-Authenticate", "OIDC")
                .header("Location", nullValue());
    }

    @Test
    void browser_opening_api_path_is_redirected_to_sign_in() {
        String location = given()
                .redirects().follow(false)
                .when().get("/api/appliances")
                .then()
                .statusCode(302)
                .extract().header("Location");
        assertTrue(location.startsWith(AUTHORIZATION_ENDPOINT + "?"), location);
        assertTrue(redirectUri(location).endsWith("/api/appliances"), location);
    }

    @Test
    void login_signs_in_and_comes_back_to_login() {
        String location = given()
                .redirects().follow(false)
                .when().get("/login")
                .then()
                .statusCode(302)
                .extract().header("Location");
        assertTrue(location.startsWith(AUTHORIZATION_ENDPOINT + "?"), location);
        assertTrue(redirectUri(location).endsWith("/login"), location);
    }

    private static String redirectUri(String location) {
        for (String pair : location.substring(location.indexOf('?') + 1).split("&")) {
            if (pair.startsWith("redirect_uri=")) {
                return URLDecoder.decode(pair.substring("redirect_uri=".length()), StandardCharsets.UTF_8);
            }
        }
        return "";
    }
}
