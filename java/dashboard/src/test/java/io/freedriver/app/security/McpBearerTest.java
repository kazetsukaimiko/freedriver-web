package io.freedriver.app.security;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keycloak bearer on {@code /autonomy/mcp} only. Cookie BFF stays on the portal.
 */
@QuarkusTest
@TestProfile(McpBearerTest.OidcOn.class)
@QuarkusTestResource(value = McpOidc.class, restrictToAnnotatedClass = true)
class McpBearerTest {

    public static class OidcOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> conf = new HashMap<>();
            conf.put("quarkus.oidc.enabled", "true");
            conf.put("quarkus.oidc.application-type", "web-app");
            conf.put("quarkus.oidc.client-id", "freedriver-api");
            conf.put("quarkus.oidc.credentials.secret", "secret");
            conf.put("quarkus.oidc.authentication.cookie-force-secure", "false");
            conf.put("quarkus.oidc.mcp.application-type", "service");
            conf.put("quarkus.oidc.mcp.client-id", McpPaths.CLIENT_ID);
            conf.put("quarkus.oidc.mcp.token.issuer", "https://server.example.com");
            conf.put("quarkus.oidc.mcp.token.audience", McpPaths.AUDIENCE);
            conf.put("quarkus.oidc.mcp.token.required-claims.azp", McpPaths.CLIENT_ID);
            conf.put("quarkus.http.test-port", "0");
            return conf;
        }
    }

    @Inject
    CommandRateLimiter limiter;

    @BeforeEach
    void resetLimiter() {
        limiter.reset();
    }

    @Test
    void good_token_is_204_without_set_cookie() {
        String token = token("mcp-sub-good", Set.of("mcp-user"), McpPaths.AUDIENCE, McpPaths.CLIENT_ID, null, null);
        given().header("Authorization", "Bearer " + token)
                .when().get("/autonomy/mcp")
                .then()
                .statusCode(204)
                .header("Set-Cookie", nullValue());
    }

    @Test
    void good_token_on_a_portal_route_is_401() {
        String token = token("mcp-sub-portal", Set.of("mcp-user"), McpPaths.AUDIENCE, McpPaths.CLIENT_ID, null, null);
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
    void session_cookie_on_mcp_is_401() {
        given().cookie("q_session", "portal-session")
                .redirects().follow(false)
                .when().get("/autonomy/mcp")
                .then()
                .statusCode(401);
    }

    @Test
    void wrong_audience_is_401() {
        for (String aud : new String[] {"freedriver-api", "account", "freedriver-dispatcher"}) {
            String token = token("mcp-sub-aud", Set.of("mcp-user"), aud, McpPaths.CLIENT_ID, null, null);
            given().header("Authorization", "Bearer " + token)
                    .when().get("/autonomy/mcp")
                    .then()
                    .statusCode(401);
        }
        String missing = token("mcp-sub-no-aud", Set.of("mcp-user"), null, McpPaths.CLIENT_ID, null, null);
        given().header("Authorization", "Bearer " + missing)
                .when().get("/autonomy/mcp")
                .then()
                .statusCode(401);
    }

    @Test
    void expired_token_is_401() {
        String token = token(
                "mcp-sub-exp",
                Set.of("mcp-user"),
                McpPaths.AUDIENCE,
                McpPaths.CLIENT_ID,
                Instant.now().minusSeconds(60),
                null);
        given().header("Authorization", "Bearer " + token)
                .when().get("/autonomy/mcp")
                .then()
                .statusCode(401);
    }

    @Test
    void other_realm_is_401() {
        String token = token(
                "mcp-sub-iss",
                Set.of("mcp-user"),
                McpPaths.AUDIENCE,
                McpPaths.CLIENT_ID,
                null,
                "https://evil.example/realms/other");
        given().header("Authorization", "Bearer " + token)
                .when().get("/autonomy/mcp")
                .then()
                .statusCode(401);
    }

    @Test
    void missing_mcp_user_is_403() {
        String token = token("mcp-sub-role", Set.of("portal-admin"), McpPaths.AUDIENCE, McpPaths.CLIENT_ID, null, null);
        given().header("Authorization", "Bearer " + token)
                .when().get("/autonomy/mcp")
                .then()
                .statusCode(403);
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

    @Test
    void allowed_call_writes_the_audit_line() {
        String token = token("mcp-sub-audit", Set.of("mcp-user"), McpPaths.AUDIENCE, McpPaths.CLIENT_ID, null, null);
        java.util.List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();
        Logger logger = Logger.getLogger(McpAudit.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                String message = record.getMessage();
                if (message != null && message.startsWith("audit mcp ")) {
                    lines.add(message);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            given().header("Authorization", "Bearer " + token)
                    .when().get("/autonomy/mcp")
                    .then()
                    .statusCode(204);
        } finally {
            logger.removeHandler(handler);
        }
        assertFalse(lines.isEmpty());
        String line = lines.getLast();
        assertTrue(line.contains(" sub=mcp-sub-audit "), line);
        assertTrue(line.contains(" azp=" + McpPaths.CLIENT_ID + " "), line);
        assertTrue(line.contains(" result=allowed"), line);
        assertFalse(line.contains(token), line);
        assertFalse(line.contains("Bearer"), line);
    }

    private static String token(
            String sub, Set<String> groups, String aud, String azp, Instant expires, String issuer) {
        var builder = Jwt.preferredUserName("ada")
                .groups(groups)
                .issuer(issuer == null ? "https://server.example.com" : issuer)
                .subject(sub)
                .claim("typ", "Bearer");
        if (azp != null) {
            builder = builder.claim("azp", azp);
        }
        if (aud != null) {
            builder = builder.audience(aud);
        }
        if (expires != null) {
            builder = builder.expiresAt(expires);
        }
        return builder.jws().keyId("1").sign("privateKey.jwk");
    }
}
