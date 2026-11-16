package io.freedriver.app.invites;

import io.freedriver.app.TestClock;
import io.freedriver.app.api.SignInLink;
import io.freedriver.app.phones.PhoneRow;
import io.freedriver.app.phones.RecordingPhoneSignIn;
import io.freedriver.app.phones.RecordingSmsPhoneList;
import io.freedriver.app.security.CsrfFilter;
import io.freedriver.ratelimit.HitWindow;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(InviteProfile.class)
class InviteResourceTest {

    @Inject
    TestClock clock;
    @Inject
    RecordingSmsPhoneList sms;
    @Inject
    RecordingPhoneSignIn keycloak;
    @Inject
    @ConfigProperty(name = "freedriver.public-base-url")
    String publicBaseUrl;
    @Inject
    HitWindow hits;
    @Inject
    @ConfigProperty(name = "freedriver.appliances.rate-limit.permits")
    int permits;
    @Inject
    @ConfigProperty(name = "freedriver.appliances.rate-limit.window")
    Duration window;

    @BeforeEach
    void reset() {
        clock.useSystemTime();
        sms.clear();
        keycloak.clear();
    }

    @Test
    @TestSecurity(user = "yuni", roles = {"portal-admin"})
    void create_returns_the_login_link_and_does_not_text_or_record_agreement() {
        Response created = csrf().body("{\"phone\":\" (555) 555-0123 \"}").post("/api/invites");
        created.then().statusCode(201);
        assertEquals("***0123", created.path("phone"));
        assertLoginLink(created, "+15555550123");
        assertNoProviderBody(created);
        assertNull(sms.list().getFirst().consentedAt());
        given().when().get("/api/phones").then().statusCode(200)
                .body("phones.size()", is(1))
                .body("phones[0].loginLink", is(SignInLink.url(publicBaseUrl, "+15555550123")))
                .body("phones[0].lastSignIn", nullValue());
        assertEquals(List.of("whitelist", "attach"), sms.steps());
        assertEquals(List.of("+15555550123"), keycloak.created());
        assertTrue(keycloak.removed().isEmpty());
    }

    @Test
    @TestSecurity(user = "invite-phone", roles = {"portal-admin"})
    void create_lists_the_number_then_creates_the_user_and_remove_undoes_it() {
        Response created = csrf().body("{\"phone\":\"+15555550123\"}").post("/api/invites");
        created.then().statusCode(201);
        assertEquals("***0123", created.path("phone"));
        assertLoginLink(created, "+15555550123");
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
    }

    @Test
    @TestSecurity(user = "yuni", roles = {"portal-admin"})
    void the_login_link_is_gone_after_the_first_sign_in() {
        sms.add(new PhoneRow("+15555550123", "Mom", "dashboard", Instant.parse("2026-10-01T00:00:00Z"), null, null));
        sms.add(new PhoneRow("+15555550199", "Dad", "dashboard", Instant.parse("2026-10-02T00:00:00Z"),
                Instant.parse("2026-10-02T00:00:00Z"), Instant.parse("2026-10-03T00:00:00Z")));
        given().when().get("/api/phones").then().statusCode(200)
                .body("phones.size()", is(2))
                .body("phones[0].loginLink", is(SignInLink.url(publicBaseUrl, "+15555550123")))
                .body("phones[0].lastSignIn", nullValue())
                .body("phones[1].loginLink", nullValue())
                .body("phones[1].lastSignIn", is("2026-10-03T00:00:00Z"));
    }

    @Test
    @TestSecurity(user = "yuni", roles = {"portal-admin"})
    void a_listed_number_is_refused_at_create() {
        sms.add(new PhoneRow("+15555550123", "Mom", "dashboard", Instant.parse("2026-10-01T00:00:00Z"), null, null));
        Response created = csrf().body("{\"phone\":\"5555550123\"}").post("/api/invites");
        created.then().statusCode(409).body("reason", is("listed"));
        assertNoProviderBody(created);
        assertTrue(sms.added().isEmpty());
        assertTrue(keycloak.created().isEmpty());
        given().when().get("/api/phones").then().body("phones.size()", is(1));
    }

    @Test
    @TestSecurity(user = "yuni", roles = {"portal-admin"})
    void an_upstream_failure_hides_the_provider_body() {
        io.restassured.specification.RequestSpecification withToken = csrf();
        sms.failList(new InviteUpstreamException("Twilio 21211 Keycloak error body +15555550123"));
        Response created = withToken.body("{\"phone\":\"+15555550123\"}").post("/api/invites");
        created.then().statusCode(502).body("error", is("unavailable"));
        assertNoProviderBody(created);
        assertFalse(created.asString().contains("+15555550123"));
    }

    /** The anonymous redeem flow is gone: its four routes and the admin list and cancel are not served. */
    @Test
    void the_removed_invite_routes_answer_404() {
        for (String path : List.of("/api/invites/redeem/start", "/api/invites/redeem/resend",
                "/api/invites/redeem/verify", "/api/invites/use")) {
            given().contentType(ContentType.JSON).body("{\"code\":\"nope\",\"phone\":\"+15555550123\"}")
                    .post(path).then().statusCode(404);
        }
    }

    @Test
    @TestSecurity(user = "yuni", roles = {"portal-admin"})
    void the_admin_invite_list_and_cancel_are_not_served() {
        given().when().get("/api/invites").then().statusCode(405);
        csrf().post("/api/invites/" + java.util.UUID.randomUUID() + "/cancel").then().statusCode(404);
        for (String path : List.of("/api/invites/redeem/start", "/api/invites/use")) {
            csrf().body("{\"code\":\"nope\"}").post(path).then().statusCode(404);
        }
    }

    /** The admin page reads its CSRF token from the phone list, then creates and removes with it. */
    @Test
    @TestSecurity(user = "yuni", roles = {"portal-admin"})
    void the_phone_list_token_creates_and_removes_a_number() {
        Response phones = given().when().get("/api/phones");
        phones.then().statusCode(200).body("phones.size()", is(0));
        String token = phones.path("csrfToken");
        assertTrue(token != null && !token.isBlank(), phones.asString());
        assertEquals(token, phones.cookie(CsrfFilter.COOKIE));

        Response created = given().contentType(ContentType.JSON)
                .cookie(CsrfFilter.COOKIE, token)
                .header(CsrfFilter.HEADER, token)
                .body("{\"phone\":\"+15555550123\"}")
                .post("/api/invites");
        created.then().statusCode(201);
        assertLoginLink(created, "+15555550123");

        given().contentType(ContentType.JSON)
                .cookie(CsrfFilter.COOKIE, token)
                .header(CsrfFilter.HEADER, token)
                .body("{\"phone\":\"+15555550123\"}")
                .post("/api/phones/remove")
                .then().statusCode(204);
        assertEquals(List.of("+15555550123"), sms.removed());
        assertEquals(List.of("+15555550123"), keycloak.removed());
    }

    @Test
    void anonymous_admin_routes_need_a_session() {
        given().contentType(ContentType.JSON).body("{\"phone\":\"+15555550123\"}").post("/api/invites").then().statusCode(401);
        given().when().get("/api/phones").then().statusCode(401);
        given().when().get("/api/jobs").then().statusCode(401);
        given().contentType(ContentType.JSON).body("{\"phone\":\"+15555550123\"}")
                .post("/api/phones/remove").then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "bob", roles = {"user"})
    void a_non_admin_is_refused() {
        given().when().get("/api/phones").then().statusCode(403);
        given().when().get("/api/jobs").then().statusCode(403);
        given().contentType(ContentType.JSON).body("{\"phone\":\"+15555550123\"}").post("/api/invites").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "yuni", roles = {"portal-admin"})
    void create_without_csrf_is_refused() {
        given().contentType(ContentType.JSON).body("{\"phone\":\"+15555550123\"}").post("/api/invites")
                .then().statusCode(400).body(is("{}"));
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
    }

    /** Invite creation and phone removal have their own windows: a full {@code invites} leaves {@code phones} open. */
    @Test
    @TestSecurity(user = "bucket-invites-full", roles = {"portal-admin"})
    void a_full_invite_window_leaves_phone_removal_open() {
        for (int i = 0; i < permits; i++) {
            csrf().body("{\"phone\":\"+1555556" + String.format("%04d", i) + "\"}").post("/api/invites")
                    .then().statusCode(201);
        }
        csrf().body("{\"phone\":\"+15555569999\"}").post("/api/invites").then().statusCode(429);
        assertEquals(permits, hits.count("invites:bucket-invites-full", window.toMillis()));

        csrf().body("{\"phone\":\"+15555560000\"}").post("/api/phones/remove").then().statusCode(204);
        assertEquals(List.of("+15555560000"), sms.removed());
        assertEquals(1, hits.count("phones:bucket-invites-full", window.toMillis()));
    }

    @Test
    @TestSecurity(user = "bucket-phones-full", roles = {"portal-admin"})
    void a_full_phone_window_leaves_invite_creation_open() {
        for (int i = 0; i < permits; i++) {
            csrf().body("{\"phone\":\"+1555557" + String.format("%04d", i) + "\"}").post("/api/phones/remove")
                    .then().statusCode(204);
        }
        csrf().body("{\"phone\":\"+15555579999\"}").post("/api/phones/remove").then().statusCode(429);
        assertEquals(permits, hits.count("phones:bucket-phones-full", window.toMillis()));

        csrf().body("{\"phone\":\"+15555578888\"}").post("/api/invites").then().statusCode(201);
        assertEquals(1, hits.count("invites:bucket-phones-full", window.toMillis()));
    }

    private void assertLoginLink(Response response, String phone) {
        String link = response.path("link");
        assertEquals(SignInLink.url(publicBaseUrl, phone), link);
        URI uri = URI.create(link);
        assertEquals(URI.create(publicBaseUrl).getHost(), uri.getHost());
        assertEquals("/sign-in", uri.getPath());
        assertNull(uri.getQuery());
        assertEquals(phone, uri.getFragment());
        assertFalse(link.contains("/invite/"));
    }

    private static void assertNoProviderBody(Response response) {
        String body = response.asString();
        assertFalse(body.contains("/invite/#"));
        assertFalse(body.contains("Twilio"));
        assertFalse(body.contains("Keycloak"));
    }

    private static io.restassured.specification.RequestSpecification csrf() {
        Response get = given().when().get("/api/phones");
        get.then().statusCode(200);
        return given().contentType(ContentType.JSON)
                .cookie(CsrfFilter.COOKIE, get.cookie(CsrfFilter.COOKIE))
                .header(CsrfFilter.HEADER, get.path("csrfToken"));
    }
}
