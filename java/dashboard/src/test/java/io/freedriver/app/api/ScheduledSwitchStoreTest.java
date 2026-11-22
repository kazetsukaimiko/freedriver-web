package io.freedriver.app.api;

import io.freedriver.app.appliances.AppliancesConfig;
import io.freedriver.app.appliances.MockAutonomy;
import io.freedriver.app.schedule.ScheduledSwitchRepository;
import io.freedriver.app.security.CallerRateLimiter;
import io.freedriver.app.security.CsrfFilter;
import io.freedriver.dashboard.api.PendingCapHeader;
import io.freedriver.dashboard.api.RateLimitBucket;
import io.freedriver.ratelimit.HitWindow;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code POST /api/mcp/scheduled-switches} spends {@code appliances.schedules}. A cookie session
 * sends the token minted by {@code GET /api/appliances}. The command window is a different key.
 */
@QuarkusTest
class ScheduledSwitchStoreTest {

    private static final String PATH = "/api/mcp/scheduled-switches";
    private static final String CALLER_A = "schedule-caller-a";
    private static final String CALLER_B = "schedule-caller-b";

    @Inject
    RedisDataSource redis;

    @Inject
    MockAutonomy mock;

    @Inject
    HitWindow hits;

    @Inject
    AppliancesConfig config;

    @Inject
    ScheduledSwitchRepository switches;

    @Inject
    TestIdentityAssociation identities;

    @BeforeEach
    void reset() {
        mock.reset();
        mock.setConfirmCommands(true);
        redis.flushall();
        switches.deleteAll();
    }

    @Test
    void a_full_schedules_window_refuses_the_next_store_and_still_switches() {
        asCaller("ada", CALLER_A, "dashboard");
        int permits = config.rateLimit().permits();
        for (int i = 0; i < permits; i++) {
            store().then().statusCode(202);
        }
        assertEquals(permits, switches.count());
        assertEquals(permits, schedules(CALLER_A));

        Response refused = store();
        refused.then().statusCode(429);
        assertEquals("", refused.asString());
        assertNull(refused.header(PendingCapHeader.NAME));
        assertEquals(permits, switches.count());
        assertEquals(permits, schedules(CALLER_A));

        command().then().statusCode(200);
        assertEquals(1, commands(CALLER_A));
        assertEquals(permits, schedules(CALLER_A));
    }

    @Test
    void a_full_commands_window_still_stores() {
        asCaller("ada", CALLER_A, "dashboard");
        int permits = config.rateLimit().permits();
        for (int i = 0; i < permits; i++) {
            command().then().statusCode(200);
        }
        assertEquals(permits, commands(CALLER_A));
        assertEquals(0, schedules(CALLER_A));

        store().then().statusCode(202);
        assertEquals(1, switches.count());
        assertEquals(CALLER_A, switches.listAll().getFirst().subject);
        assertEquals(1, schedules(CALLER_A));
        assertEquals(permits, commands(CALLER_A));

        command().then().statusCode(429);
        assertEquals(permits, commands(CALLER_A));
    }

    @Test
    void callers_keep_separate_schedules_windows() {
        asCaller("ada", CALLER_A, "dashboard");
        int permits = config.rateLimit().permits();
        for (int i = 0; i < permits; i++) {
            store().then().statusCode(202);
        }
        store().then().statusCode(429);
        assertEquals(permits, switches.count());
        assertEquals(permits, schedules(CALLER_A));

        asCaller("bea", CALLER_B, "dashboard");
        store().then().statusCode(202);
        assertEquals(permits + 1, switches.count());
        assertEquals(1, switches.find("subject", CALLER_B).count());
        assertEquals(1, schedules(CALLER_B));
        assertEquals(permits, schedules(CALLER_A));
    }

    @Test
    void a_cookie_post_without_the_csrf_token_spends_no_permit() {
        asCaller("ada", CALLER_A, "dashboard");
        given().contentType(ContentType.JSON)
                .body(body())
                .when().post(PATH)
                .then().statusCode(400).body(is("{}"));
        assertEquals(0, schedules(CALLER_A));
        assertEquals(0, switches.count());

        store().then().statusCode(202);
        assertEquals(1, schedules(CALLER_A));
        assertEquals(1, switches.count());
    }

    @Test
    void portal_admin_stores_with_the_csrf_token() {
        asCaller("portal", "schedule-portal", "portal-admin");
        given().contentType(ContentType.JSON)
                .body(body())
                .when().post(PATH)
                .then().statusCode(400).body(is("{}"));
        assertEquals(0, schedules("schedule-portal"));

        store().then().statusCode(202);
        assertEquals(1, schedules("schedule-portal"));
        assertEquals("schedule-portal", switches.listAll().getFirst().subject);
    }

    @Test
    void a_principal_name_spends_one_permit_and_stores_nothing() {
        asCaller("principal-only", null, "dashboard");
        csrf().body(body()).when().post(PATH).then().statusCode(400);
        assertEquals(1, schedules("principal-only"));
        assertEquals(0, switches.count());
    }

    @Test
    void an_authorization_header_with_oidc_off_stays_a_cookie_caller() {
        asCaller("ada", CALLER_A, "dashboard");
        given().contentType(ContentType.JSON)
                .header("Authorization", "Bearer not-a-token")
                .body(body())
                .when().post(PATH)
                .then().statusCode(400).body(is("{}"));
        assertEquals(0, schedules(CALLER_A));
        assertEquals(0, switches.count());

        csrf().header("Authorization", "Bearer not-a-token")
                .body(body())
                .when().post(PATH)
                .then().statusCode(202);
        assertEquals(1, schedules(CALLER_A));
        assertEquals(1, switches.count());
        assertEquals(CALLER_A, switches.listAll().getFirst().subject);
    }

    private void asCaller(String principal, String sub, String role) {
        QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(principal))
                .addRoles(Set.of(role));
        if (sub != null) {
            builder.addAttribute("sub", sub);
        }
        SecurityIdentity identity = builder.build();
        identities.setTestIdentity(identity);
    }

    private RequestSpecification csrf() {
        Response get = given().when().get("/api/appliances");
        get.then().statusCode(200);
        return given()
                .contentType(ContentType.JSON)
                .cookie(CsrfFilter.COOKIE, get.cookie(CsrfFilter.COOKIE))
                .header(CsrfFilter.HEADER, get.path("csrfToken"));
    }

    private Response store() {
        return csrf().body(body()).when().post(PATH);
    }

    private Response command() {
        String name = MockAutonomy.FIXTURE_NAMES.get(0);
        return csrf().body("{\"on\":true}")
                .when().post("/api/appliances/" + MockAutonomy.INSTANCE_ID + "/" + name);
    }

    private static String body() {
        return "{\"instanceId\":\"" + MockAutonomy.INSTANCE_ID
                + "\",\"applianceName\":\"" + MockAutonomy.FIXTURE_NAMES.get(0)
                + "\",\"on\":false,\"dueAt\":\"" + Instant.now().plusSeconds(120) + "\"}";
    }

    private int schedules(String caller) {
        return hits.count(CallerRateLimiter.key(RateLimitBucket.APPLIANCES_SCHEDULES, caller), windowMs());
    }

    private int commands(String caller) {
        return hits.count(CallerRateLimiter.key(RateLimitBucket.APPLIANCES_COMMANDS, caller), windowMs());
    }

    private long windowMs() {
        return config.rateLimit().window().toMillis();
    }
}
