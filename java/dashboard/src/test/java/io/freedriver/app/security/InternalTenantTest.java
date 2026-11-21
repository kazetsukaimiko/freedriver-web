package io.freedriver.app.security;

import io.quarkus.redis.datasource.RedisDataSource;
import io.freedriver.app.appliances.ApplianceAudit;
import io.freedriver.app.appliances.AppliancesConfig;
import io.freedriver.app.appliances.MockAutonomy;
import io.freedriver.app.schedule.ScheduledSwitchRepository;
import io.freedriver.dashboard.api.RateLimitBucket;
import io.freedriver.ratelimit.HitWindow;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import org.jboss.logmanager.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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

    @Inject
    HitWindow hits;

    @Inject
    AppliancesConfig config;

    @Inject
    CallerRateLimiter limiter;

    @Inject
    ScheduledSwitchRepository switches;

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

    /** No exchanged token carries {@code mcp-debug}; the debug tool checks that role on the caller's own token. */
    @Test
    void an_exchanged_mcp_user_token_reads_debug() {
        given().header("Authorization", "Bearer " + exchanged("mcp-sub", Set.of("mcp-user")))
                .when()
                .get("/api/mcp/debug")
                .then()
                .statusCode(200)
                .body("health", is("UP"));
    }

    @Test
    void a_freedriver_api_token_from_another_client_is_401() {
        String other = Jwt.preferredUserName("ada")
                .groups(Set.of("mcp-user", "dashboard"))
                .issuer("https://server.example.com")
                .subject("mcp-sub-other")
                .audience(EXCHANGED_AUDIENCE)
                .claim("azp", "freedriver-portal-jobs")
                .claim("typ", "Bearer")
                .jws().keyId("1").sign("privateKey.jwk");
        given().header("Authorization", "Bearer " + other)
                .when()
                .get("/api/mcp/appliances")
                .then()
                .statusCode(401);
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

    /** The internal tenant skips CSRF, and its command still spends the caller's {@code appliances.commands} window. */
    @Test
    void a_csrf_exempt_bearer_command_spends_its_window() {
        String hallway = MockAutonomy.FIXTURE_NAMES.get(0);
        String key = "appliances.commands:mcp-limit-sub";
        long windowMs = config.rateLimit().window().toMillis();
        assertEquals(0, hits.count(key, windowMs));
        given().header("Authorization", "Bearer " + exchanged("mcp-limit-sub", Set.of("mcp-user", "dashboard")))
                .contentType("application/json")
                .body("{\"on\":true}")
                .when()
                .post("/api/appliances/" + MockAutonomy.INSTANCE_ID + "/" + hallway)
                .then()
                .statusCode(200);
        assertEquals(1, hits.count(key, windowMs));
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

    @Test
    void an_internal_bearer_stores_a_switch_without_a_csrf_token() {
        switches.deleteAll();
        String subject = "schedule-bearer-sub";
        String key = CallerRateLimiter.key(RateLimitBucket.APPLIANCES_SCHEDULES, subject);
        long windowMs = config.rateLimit().window().toMillis();
        assertEquals(0, hits.count(key, windowMs));

        schedule(subject, Set.of("mcp-user", "dashboard")).then().statusCode(202);

        assertEquals(1, hits.count(key, windowMs));
        assertEquals(1, switches.count());
        assertEquals(subject, switches.listAll().getFirst().subject);
    }

    @Test
    void a_full_schedules_window_refuses_the_exchanged_token() {
        switches.deleteAll();
        String subject = "schedule-full-sub";
        String key = CallerRateLimiter.key(RateLimitBucket.APPLIANCES_SCHEDULES, subject);
        long windowMs = config.rateLimit().window().toMillis();
        int taken = 0;
        while (limiter.tryAcquire(RateLimitBucket.APPLIANCES_SCHEDULES, subject)) {
            taken++;
        }
        assertEquals(config.rateLimit().permits(), taken);
        assertEquals(config.rateLimit().permits(), hits.count(key, windowMs));

        Response refused = schedule(subject, Set.of("mcp-user", "dashboard"));
        refused.then().statusCode(429);
        assertEquals("", refused.asString());
        assertEquals(0, switches.count());
        assertEquals(config.rateLimit().permits(), hits.count(key, windowMs));
    }

    @Test
    void an_exchanged_mcp_user_cannot_store_a_switch() {
        switches.deleteAll();
        schedule("schedule-reader-sub", Set.of("mcp-user")).then().statusCode(403);
        assertEquals(0, switches.count());
    }

    private Response schedule(String subject, Set<String> roles) {
        return given().header("Authorization", "Bearer " + exchanged(subject, roles))
                .contentType("application/json")
                .body(switchBody())
                .when()
                .post("/api/mcp/scheduled-switches");
    }

    private static String switchBody() {
        return "{\"instanceId\":\"" + MockAutonomy.INSTANCE_ID
                + "\",\"applianceName\":\"" + MockAutonomy.FIXTURE_NAMES.get(0)
                + "\",\"on\":false,\"dueAt\":\"" + Instant.now().plusSeconds(120) + "\"}";
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
