package io.freedriver.app.security;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.jwt.build.Jwt;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;

/**
 * The portal takes the cookie session, not a bearer. {@code /autonomy/mcp} is the autonomy-mcp
 * process: the dashboard has no MCP tenant, and a Grok bearer is refused on every dashboard route.
 */
@QuarkusTest
@TestProfile(PortalBearerTest.OidcOn.class)
@QuarkusTestResource(value = PortalOidc.class, restrictToAnnotatedClass = true)
class PortalBearerTest {

    private static final String AUDIENCE = "https://app.freedriver.io/autonomy/mcp";
    private static final String CLIENT_ID = "freedriver-grok-mcp";

    public static class OidcOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> conf = new HashMap<>();
            conf.put("quarkus.oidc.enabled", "true");
            conf.put("quarkus.oidc.application-type", "web-app");
            conf.put("quarkus.oidc.client-id", "freedriver-api");
            conf.put("quarkus.oidc.credentials.secret", "secret");
            conf.put("quarkus.oidc.authentication.cookie-force-secure", "false");
            conf.put("quarkus.http.test-port", "0");
            return conf;
        }
    }

    @Test
    void mcp_token_on_a_portal_route_is_401() {
        String token = token();
        given().header("Authorization", "Bearer " + token)
                .header("X-Requested-With", "JavaScript")
                .redirects().follow(false)
                .when().get("/api/appliances")
                .then()
                .statusCode(401);
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/hello")
                .then()
                .statusCode(401);
    }

    @Test
    void the_dashboard_does_not_serve_autonomy_mcp() {
        given().header("Authorization", "Bearer " + token())
                .redirects().follow(false)
                .when().get("/autonomy/mcp")
                .then()
                .statusCode(401);
        given().header("Authorization", "Bearer " + token())
                .redirects().follow(false)
                .contentType("application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
                .when().post("/autonomy/mcp")
                .then()
                .statusCode(401);
    }

    @Test
    void cookie_and_csrf_on_the_portal_stay() {
        given().header("X-Requested-With", "JavaScript")
                .redirects().follow(false)
                .when().get("/api/appliances")
                .then()
                .statusCode(499);
        given().redirects().follow(false)
                .when().get("/api/hello")
                .then()
                .statusCode(200);
        given().redirects().follow(false)
                .when().get("/login")
                .then()
                .statusCode(302);
    }

    private static String token() {
        return Jwt.preferredUserName("ada")
                .groups(Set.of("mcp-user"))
                .issuer("https://server.example.com")
                .subject("mcp-sub-portal")
                .audience(AUDIENCE)
                .claim("azp", CLIENT_ID)
                .claim("typ", "Bearer")
                .jws().keyId("1").sign("privateKey.jwk");
    }
}
