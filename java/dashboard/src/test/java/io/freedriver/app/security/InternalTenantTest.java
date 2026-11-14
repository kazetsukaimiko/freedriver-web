package io.freedriver.app.security;

import io.quarkus.redis.datasource.RedisDataSource;
import io.freedriver.app.appliances.ApplianceAudit;
import io.freedriver.app.appliances.MockAutonomy;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import org.jboss.logmanager.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * autonomy-mcp's calls with OIDC on: the connecting user's token, exchanged for audience
 * {@code freedriver-api}, resolves the {@code internal} tenant on {@code /api/mcp} and
 * {@code /api/appliances}. The dashboard's own {@code @RolesAllowed} judges that user's roles.
 */
@QuarkusTest
@TestProfile(PortalBearerTest.OidcOn.class)
@QuarkusTestResource(value = InternalOidc.class, restrictToAnnotatedClass = true)
class InternalTenantTest {

    private static final String EXCHANGED_AUDIENCE = "freedriver-api";
    private static final String EXCHANGE_CLIENT = "freedriver-autonomy-mcp";

    @Inject
    RedisDataSource redis;

    @Inject
    MockAutonomy mock;

    @BeforeEach
    void reset() {
        mock.reset();
        redis.flushall();
        mock.setConfirmCommands(true);
    }

    @Test
    void an_mcp_user_token_reads_mcp_ops_without_a_session_cookie() {
        given().header("Authorization", "Bearer " + exchanged("mcp-sub", Set.of("mcp-user")))
                .when()
                .get("/api/mcp/appliances")
                .then()
                .statusCode(200)
                .header("Set-Cookie", nullValue());
    }

    @Test
    void a_user_without_dashboard_is_refused_by_the_dashboards_roles() {
        String hallway = MockAutonomy.FIXTURE_NAMES.get(0);
        List<String> lines = auditLines(() -> given()
                .header("Authorization", "Bearer " + exchanged("mcp-reader-sub", Set.of("mcp-user")))
                .contentType("application/json")
                .body("{\"on\":true}")
                .when()
                .post("/api/appliances/" + MockAutonomy.INSTANCE_ID + "/" + hallway)
                .then()
                .statusCode(403));
        assertTrue(lines.isEmpty(), lines::toString);
        assertTrue(mock.publishedCommands().isEmpty(), () -> mock.publishedCommands().toString());
    }

    @Test
    void switch_skips_csrf_and_audits_the_users_own_subject() {
        String hallway = MockAutonomy.FIXTURE_NAMES.get(0);
        List<String> lines = auditLines(() -> given()
                .header("Authorization", "Bearer " + exchanged("mcp-switch-sub", Set.of("mcp-user", "dashboard")))
                .contentType("application/json")
                .body("{\"on\":true}")
                .when()
                .post("/api/appliances/" + MockAutonomy.INSTANCE_ID + "/" + hallway)
                .then()
                .statusCode(200)
                .body("timeout", is(false)));
        assertEquals(1, lines.size());
        String line = lines.getFirst();
        assertTrue(line.matches(
                "audit appliance when=\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z"
                        + " instance=" + MockAutonomy.INSTANCE_ID
                        + " appliance=" + Pattern.quote(hallway) + " on=true commandId=[0-9a-fA-F-]{36}"
                        + " result=confirmed subject=mcp-switch-sub"),
                line);
    }

    @Test
    void a_token_for_another_audience_is_401() {
        String grok = Jwt.preferredUserName("ada")
                .groups(Set.of("mcp-user", "dashboard"))
                .issuer("https://server.example.com")
                .subject("mcp-sub-grok")
                .audience("https://app.freedriver.io/autonomy/mcp")
                .claim("azp", "freedriver-grok-mcp")
                .claim("typ", "Bearer")
                .jws().keyId("1").sign("privateKey.jwk");
        given().header("Authorization", "Bearer " + grok)
                .when()
                .get("/api/mcp/appliances")
                .then()
                .statusCode(401);
    }

    @Test
    void the_exchanged_token_is_refused_off_the_internal_paths() {
        given().header("Authorization", "Bearer " + exchanged("mcp-sub", Set.of("mcp-user", "dashboard")))
                .when()
                .get("/api/hello")
                .then()
                .statusCode(401);
    }

    @Test
    void the_old_internal_headers_do_not_authenticate() {
        given().header("X-Freedriver-Internal", "test-internal-secret")
                .header("X-Freedriver-Subject", "mcp-sub")
                .header("X-Freedriver-Roles", "mcp-user,dashboard")
                .header("X-Requested-With", "JavaScript")
                .redirects().follow(false)
                .when()
                .get("/api/mcp/appliances")
                .then()
                .statusCode(499);
    }

    /** The token Keycloak answers the exchange with: the user's sub and roles, audience freedriver-api. */
    private static String exchanged(String subject, Set<String> roles) {
        return Jwt.preferredUserName("ada")
                .groups(roles)
                .issuer("https://server.example.com")
                .subject(subject)
                .audience(EXCHANGED_AUDIENCE)
                .claim("azp", EXCHANGE_CLIENT)
                .claim("typ", "Bearer")
                .jws().keyId("1").sign("privateKey.jwk");
    }

    private static List<String> auditLines(Runnable action) {
        Logger logger = Logger.getLogger(ApplianceAudit.class.getName());
        List<String> lines = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                String message = record.getMessage();
                if (message != null && message.startsWith("audit appliance ")) {
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
            action.run();
        } finally {
            logger.removeHandler(handler);
        }
        return lines;
    }
}
