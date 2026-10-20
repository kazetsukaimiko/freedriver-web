package io.freedriver.app.invites;

import io.freedriver.app.phones.PhoneRow;
import io.freedriver.app.phones.RecordingPhoneSignIn;
import io.freedriver.app.phones.RecordingSmsPhoneList;
import io.freedriver.app.security.CsrfFilter;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(InviteProfile.class)
class InviteResourceTest {

    @Inject
    InviteStore store;
    @Inject
    RecordingSmsPhoneList sms;
    @Inject
    RecordingPhoneSignIn keycloak;

    @BeforeEach
    void reset() {
        store.clear();
        store.clock(Clock.systemUTC());
        sms.clear();
        keycloak.clear();
    }

    @Test
    @TestSecurity(user = "yuni", roles = {"portal-admin"})
    void create_shows_the_link_once_and_lists_no_number() {
        Response created = csrf().body("{\"name\":\" Mom \"}").post("/api/invites");
        created.then().statusCode(201);
        String link = created.path("link");
        assertTrue(link.startsWith("https://app.freedriver.io/invite/#"));
        String code = link.substring(link.indexOf('#') + 1);
        assertEquals(43, code.length());
        assertFalse(store.fileContains(code));
        assertEquals("Mom", created.path("invite.name"));

        given().when().get("/api/invites").then().statusCode(200).body("invites.size()", org.hamcrest.Matchers.is(1));
        given().when().get("/api/phones").then().statusCode(200).body("phones.size()", org.hamcrest.Matchers.is(0));
        assertTrue(sms.removed().isEmpty());
        assertTrue(keycloak.removed().isEmpty());
    }

    @Test
    @TestSecurity(user = "yuni", roles = {"portal-admin"})
    void one_invite_is_used_once_including_two_concurrent_callers() throws Exception {
        String code = codeOf(csrf().body("{\"name\":\"Mom\"}").post("/api/invites"));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<InviteStore.UseResult> first = pool.submit(() -> {
                start.await();
                return store.use(code);
            });
            Future<InviteStore.UseResult> second = pool.submit(() -> {
                start.await();
                return store.use(code);
            });
            start.countDown();
            List<InviteStore.UseResult> results = List.of(first.get(), second.get());
            assertEquals(1, results.stream().filter(result -> result == InviteStore.UseResult.USED).count());
            assertEquals(1, results.stream().filter(result -> result == InviteStore.UseResult.REJECTED).count());
        } finally {
            pool.shutdownNow();
        }
        given().contentType(ContentType.JSON).body("{\"code\":\"" + code + "\"}")
                .post("/api/invites/use").then().statusCode(409).body("used", org.hamcrest.Matchers.is(false));
        given().when().get("/api/invites").then().body("invites.size()", org.hamcrest.Matchers.is(0));
    }

    @Test
    @TestSecurity(user = "yuni", roles = {"portal-admin"})
    void expired_and_cancelled_invites_fail_the_same_update() {
        String expired = codeOf(csrf().body("{\"name\":\"Mom\"}").post("/api/invites"));
        store.clock(Clock.fixed(Instant.now().plus(Duration.ofHours(72)), ZoneOffset.UTC));
        given().contentType(ContentType.JSON).body("{\"code\":\"" + expired + "\"}")
                .post("/api/invites/use").then().statusCode(409);

        store.clock(Clock.systemUTC());
        Response created = csrf().body("{\"name\":\"Dad\"}").post("/api/invites");
        String id = created.path("invite.id");
        String code = codeOf(created);
        csrf().post("/api/invites/" + id + "/cancel").then().statusCode(204);
        given().contentType(ContentType.JSON).body("{\"code\":\"" + code + "\"}")
                .post("/api/invites/use").then().statusCode(409);
    }

    @Test
    void anonymous_invite_use_stays_open() {
        given().contentType(ContentType.JSON).body("{\"code\":\"nope\"}")
                .post("/api/invites/use").then().statusCode(409).body("used", org.hamcrest.Matchers.is(false));
    }

    @Test
    void anonymous_admin_routes_need_a_session() {
        given().when().get("/api/invites").then().statusCode(401);
        given().contentType(ContentType.JSON).body("{\"name\":\"Mom\"}").post("/api/invites").then().statusCode(401);
        given().when().get("/api/phones").then().statusCode(401);
        given().contentType(ContentType.JSON).body("{\"phone\":\"+15555550123\"}")
                .post("/api/phones/remove").then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "bob", roles = {"user"})
    void a_non_admin_is_refused() {
        given().when().get("/api/invites").then().statusCode(403);
        given().contentType(ContentType.JSON).body("{\"name\":\"Mom\"}").post("/api/invites").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "yuni", roles = {"portal-admin"})
    void create_without_csrf_is_refused() {
        given().contentType(ContentType.JSON).body("{\"name\":\"Mom\"}").post("/api/invites")
                .then().statusCode(400).body(org.hamcrest.Matchers.is("{}"));
        assertTrue(store.open().isEmpty());
    }

    @Test
    @TestSecurity(user = "yuni", roles = {"portal-admin"})
    void removal_takes_the_user_out_of_phone_sign_in() {
        sms.add(new PhoneRow("+15555550123", "Mom", "dashboard", Instant.parse("2026-10-01T00:00:00Z"), null, null));
        csrf().body("{\"phone\":\"+15555550123\"}").post("/api/phones/remove").then().statusCode(204);
        assertEquals(List.of("+15555550123"), sms.removed());
        assertEquals(List.of("+15555550123"), keycloak.removed());
        given().when().get("/api/phones").then().body("phones.size()", org.hamcrest.Matchers.is(0));
    }

    @Test
    @TestSecurity(user = "quota", roles = {"portal-admin"})
    void invite_creates_hit_the_app_rate_limit() {
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            statuses.add(csrf().body("{\"name\":\"Person " + i + "\"}").post("/api/invites").statusCode());
        }
        assertEquals(List.of(201, 201, 201, 201, 201, 201, 201, 201, 429), statuses);
    }

    private static io.restassured.specification.RequestSpecification csrf() {
        Response get = given().when().get("/api/invites");
        get.then().statusCode(200);
        return given().contentType(ContentType.JSON)
                .cookie(CsrfFilter.COOKIE, get.cookie(CsrfFilter.COOKIE))
                .header(CsrfFilter.HEADER, get.path("csrfToken"));
    }

    private static String codeOf(Response created) {
        created.then().statusCode(201);
        String link = created.path("link");
        return link.substring(link.indexOf('#') + 1);
    }
}
