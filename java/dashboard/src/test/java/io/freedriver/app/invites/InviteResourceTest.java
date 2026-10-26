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
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void create_from_a_phone_does_not_mint_a_link() {
        Response created = csrf().body("{\"phone\":\" (555) 555-0123 \"}").post("/api/invites");
        created.then().statusCode(201);
        assertEquals("***0123", created.path("phone"));
        assertNull(created.path("link"));
        assertNoProviderBody(created);
        assertFalse(created.asString().contains("555555"));
        assertTrue(store.open().isEmpty());
        given().when().get("/api/invites").then().statusCode(200).body("invites.size()", is(0));
        given().when().get("/api/phones").then().statusCode(200).body("phones.size()", is(1));
        assertTrue(sms.sent().isEmpty());
        assertEquals(List.of("whitelist", "attach"), sms.steps());
        assertEquals(List.of("+15555550123"), keycloak.created());
        assertTrue(keycloak.removed().isEmpty());
    }

    @Test
    @TestSecurity(user = "invite-phone", roles = {"portal-admin"})
    void create_lists_the_number_then_creates_the_user_and_remove_undoes_it() {
        Response created = csrf().body("{\"phone\":\"+15555550123\"}").post("/api/invites");
        created.then().statusCode(201);
        String body = created.asString();
        assertEquals("***0123", created.path("phone"));
        assertFalse(body.contains("555555"));
        assertFalse(body.contains("link"));
        assertTrue(store.open().isEmpty());
        assertEquals(List.of("whitelist", "attach"), sms.steps());
        assertEquals(List.of("+15555550123"), keycloak.created());
        assertEquals(List.of(RecordingPhoneSignIn.USERNAME), sms.usernames());
        given().when().get("/api/phones").then().statusCode(200).body("phones.size()", org.hamcrest.Matchers.is(1));

        csrf().body("{\"phone\":\"+15555550123\"}").post("/api/invites").then().statusCode(409);
        assertEquals(List.of("+15555550123"), keycloak.created());

        csrf().body("{\"phone\":\"5555550123\"}").post("/api/invites").then().statusCode(409);
        csrf().body("{\"phone\":\"+441234567890\"}").post("/api/invites").then().statusCode(400);

        csrf().body("{\"phone\":\"+15555550123\"}").post("/api/phones/remove").then().statusCode(204);
        assertEquals(List.of("+15555550123"), sms.removed());
        assertEquals(List.of("+15555550123"), keycloak.removed());
        given().when().get("/api/phones").then().body("phones.size()", org.hamcrest.Matchers.is(0));
    }

    @Test
    @TestSecurity(user = "yuni", roles = {"portal-admin"})
    void a_name_does_not_mint_a_link() {
        Response created = csrf().body("{\"name\":\"Mom\"}").post("/api/invites");
        created.then().statusCode(400).body("reason", is("phone"));
        assertNoProviderBody(created);
        assertTrue(store.open().isEmpty());
    }

    @Test
    @TestSecurity(user = "yuni", roles = {"portal-admin"})
    void a_listed_number_is_refused_at_create() {
        sms.add(new PhoneRow("+15555550123", "Mom", "dashboard", Instant.parse("2026-10-01T00:00:00Z"), null, null));
        Response created = csrf().body("{\"phone\":\"5555550123\"}").post("/api/invites");
        created.then().statusCode(409).body("reason", is("listed"));
        assertNoProviderBody(created);
        assertTrue(store.open().isEmpty());
        assertTrue(sms.sent().isEmpty());
        assertTrue(sms.added().isEmpty());
        assertTrue(keycloak.created().isEmpty());
        given().when().get("/api/phones").then().body("phones.size()", is(1));
    }

    @Test
    @TestSecurity(user = "yuni", roles = {"portal-admin"})
    void an_upstream_failure_hides_the_provider_body() {
        sms.failList(new InviteUpstreamException("Twilio 21211 Keycloak error body +15555550123"));
        Response created = csrf().body("{\"phone\":\"+15555550123\"}").post("/api/invites");
        created.then().statusCode(502).body("error", is("unavailable"));
        assertNoProviderBody(created);
        assertFalse(created.asString().contains("+15555550123"));
        assertTrue(store.open().isEmpty());
    }

    @Test
    @TestSecurity(user = "yuni", roles = {"portal-admin"})
    void an_existing_link_still_works_until_it_expires() {
        InviteStore.Created existing = store.create("Mom");
        given().when().get("/api/invites").then().statusCode(200)
                .body("invites.size()", is(1))
                .body("invites[0].name", is("Mom"));
        given().contentType(ContentType.JSON).body("{\"code\":\"" + existing.code() + "\"}")
                .post("/api/invites/use").then().statusCode(200).body("used", is(true));
        given().when().get("/api/invites").then().body("invites.size()", is(0));
    }

    @Test
    @TestSecurity(user = "yuni", roles = {"portal-admin"})
    void one_invite_is_used_once_including_two_concurrent_callers() throws Exception {
        String code = store.create("Mom").code();
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
                .post("/api/invites/use").then().statusCode(409).body("used", is(false));
        given().when().get("/api/invites").then().body("invites.size()", is(0));
    }

    @Test
    @TestSecurity(user = "yuni", roles = {"portal-admin"})
    void expired_and_cancelled_invites_fail_the_same_update() {
        String expired = store.create("Mom").code();
        store.clock(Clock.fixed(Instant.now().plus(Duration.ofHours(72)), ZoneOffset.UTC));
        given().contentType(ContentType.JSON).body("{\"code\":\"" + expired + "\"}")
                .post("/api/invites/use").then().statusCode(409);

        store.clock(Clock.systemUTC());
        InviteStore.Created created = store.create("Dad");
        csrf().post("/api/invites/" + created.invite().id() + "/cancel").then().statusCode(204);
        String code = created.code();
        given().contentType(ContentType.JSON).body("{\"code\":\"" + code + "\"}")
                .post("/api/invites/use").then().statusCode(409);
    }

    @Test
    void anonymous_invite_use_stays_open() {
        given().contentType(ContentType.JSON).body("{\"code\":\"nope\"}")
                .post("/api/invites/use").then().statusCode(409).body("used", is(false));
    }

    @Test
    void anonymous_admin_routes_need_a_session() {
        given().when().get("/api/invites").then().statusCode(401);
        given().contentType(ContentType.JSON).body("{\"phone\":\"+15555550123\"}").post("/api/invites").then().statusCode(401);
        given().when().get("/api/phones").then().statusCode(401);
        given().contentType(ContentType.JSON).body("{\"phone\":\"+15555550123\"}")
                .post("/api/phones/remove").then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "bob", roles = {"user"})
    void a_non_admin_is_refused() {
        given().when().get("/api/invites").then().statusCode(403);
        given().contentType(ContentType.JSON).body("{\"phone\":\"+15555550123\"}").post("/api/invites").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "yuni", roles = {"portal-admin"})
    void create_without_csrf_is_refused() {
        given().contentType(ContentType.JSON).body("{\"phone\":\"+15555550123\"}").post("/api/invites")
                .then().statusCode(400).body(is("{}"));
        assertTrue(store.open().isEmpty());
    }

    @Test
    @TestSecurity(user = "yuni", roles = {"portal-admin"})
    void removal_takes_the_user_out_of_phone_sign_in() {
        sms.add(new PhoneRow("+15555550123", "Mom", "dashboard", Instant.parse("2026-10-01T00:00:00Z"), null, null));
        csrf().body("{\"phone\":\"+15555550123\"}").post("/api/phones/remove").then().statusCode(204);
        assertEquals(List.of("+15555550123"), sms.removed());
        assertEquals(List.of("+15555550123"), keycloak.removed());
        given().when().get("/api/phones").then().body("phones.size()", is(0));
    }

    @Test
    @TestSecurity(user = "quota", roles = {"portal-admin"})
    void invite_creates_hit_the_app_rate_limit() {
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            String phone = "+1555555" + String.format("%04d", i);
            statuses.add(csrf().body("{\"phone\":\"" + phone + "\"}").post("/api/invites").statusCode());
        }
        assertEquals(List.of(201, 201, 201, 201, 201, 201, 201, 201, 429), statuses);
        assertTrue(store.open().isEmpty());
    }

    private static void assertNoProviderBody(Response response) {
        String body = response.asString();
        assertFalse(body.contains("/invite/#"));
        assertFalse(body.contains("Twilio"));
        assertFalse(body.contains("Keycloak"));
    }

    private static io.restassured.specification.RequestSpecification csrf() {
        Response get = given().when().get("/api/invites");
        get.then().statusCode(200);
        return given().contentType(ContentType.JSON)
                .cookie(CsrfFilter.COOKIE, get.cookie(CsrfFilter.COOKIE))
                .header(CsrfFilter.HEADER, get.path("csrfToken"));
    }
}
