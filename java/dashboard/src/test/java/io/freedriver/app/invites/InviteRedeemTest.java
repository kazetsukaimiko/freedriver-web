package io.freedriver.app.invites;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(InviteProfile.class)
class InviteRedeemTest {

    private static final String WORDING = InviteWording.TEXT;

    @Inject
    InviteStore store;
    @Inject
    RecordingSmsPhoneList sms;
    @Inject
    RecordingPhoneSignIn keycloak;
    @Inject
    ObjectMapper json;

    @BeforeEach
    void reset() {
        store.clear();
        store.clock(Clock.systemUTC());
        sms.clear();
        keycloak.clear();
    }

    @Test
    @TestSecurity(user = "redeem", roles = {"portal-admin"})
    void a_valid_invite_goes_from_confirm_to_sign_in_with_no_admin_role() throws Exception {
        String code = invite("Mom");
        start(code, "+15555550123").then().statusCode(200).body("codesSent", is(1));
        assertEquals(1, sms.agreements().size());
        assertTrue(keycloak.created().isEmpty());

        given().contentType(ContentType.JSON).body(json.writeValueAsString(Map.of("code", code, "otp", "000000")))
                .post("/api/invites/redeem/verify")
                .then().statusCode(400).body("error", is("invalid-code"));
        assertTrue(keycloak.created().isEmpty());
        assertTrue(sms.added().isEmpty());

        Response verified = given().contentType(ContentType.JSON)
                .body(json.writeValueAsString(Map.of("code", code, "otp", "123456")))
                .post("/api/invites/redeem/verify");
        verified.then().statusCode(200).body("next", is("/login"));
        assertNull(verified.getCookie("q_session"));
        assertEquals(java.util.List.of("+15555550123"), keycloak.created());
        assertEquals("Mom", sms.added().getFirst().name());
        assertEquals("dashboard", sms.added().getFirst().role());
        assertEquals(RecordingPhoneSignIn.USERNAME, sms.usernames().getFirst());
        given().contentType(ContentType.JSON).body(json.writeValueAsString(Map.of("code", code)))
                .post("/api/invites/use").then().statusCode(409);
    }

    @Test
    @TestSecurity(user = "redeem", roles = {"portal-admin"})
    void three_submissions_and_three_codes_stop_the_invite() throws Exception {
        String code = invite("Mom");
        start(code, "5555550101").then().statusCode(200);
        start(code, "5555550102").then().statusCode(200);
        start(code, "(555) 555-0103").then().statusCode(200).body("codesSent", is(3));
        start(code, "5555550104").then().statusCode(409).body("error", is("dead"));

        String resend = invite("Dad");
        start(resend, "5555550105").then().statusCode(200).body("codesSent", is(1));
        resend(resend).then().statusCode(200).body("codesSent", is(2));
        resend(resend).then().statusCode(200).body("codesSent", is(3));
        resend(resend).then().statusCode(409).body("error", is("codes"));
        given().contentType(ContentType.JSON).body(json.writeValueAsString(Map.of("code", resend, "otp", "123456")))
                .post("/api/invites/redeem/verify").then().statusCode(200).body("next", is("/login"));
    }

    @Test
    @TestSecurity(user = "redeem", roles = {"portal-admin"})
    void expired_used_and_listed_numbers_do_not_sign_in() throws Exception {
        String expired = invite("Mom");
        store.clock(Clock.fixed(Instant.now().plus(Duration.ofHours(72)), ZoneOffset.UTC));
        start(expired, "+15555550123").then().statusCode(409).body("error", is("dead"));

        store.clock(Clock.systemUTC());
        String used = invite("Dad");
        assertEquals(InviteStore.UseResult.USED, store.use(used));
        start(used, "+15555550123").then().statusCode(409).body("error", is("dead"));

        String listed = invite("Sis");
        sms.add(new PhoneRow("+15555550123", "Sis", "dashboard", Instant.EPOCH, null, null));
        start(listed, "+15555550123").then().statusCode(409).body("error", is("already-listed"));
        assertTrue(sms.sent().isEmpty());
        assertTrue(keycloak.created().isEmpty());
    }

    @Test
    @TestSecurity(user = "redeem", roles = {"portal-admin"})
    void an_older_invite_file_still_accepts_a_submission() throws Exception {
        String code = invite("Mom");
        Path file = Path.of("target/invite-tests/invites.json");
        var tree = json.readTree(file.toFile());
        for (var row : tree) {
            ((ObjectNode) row).remove("submissions");
            ((ObjectNode) row).remove("codesSent");
            ((ObjectNode) row).remove("pendingPhone");
        }
        json.writeValue(file.toFile(), tree);
        start(code, "+15555550123").then().statusCode(200).body("codesSent", is(1));
    }

    @Test
    @TestSecurity(user = "redeem-limit", roles = {"portal-admin"})
    void redeem_hits_the_app_rate_limit() throws Exception {
        String code = invite("Mom");
        java.util.List<Integer> statuses = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) {
            statuses.add(start(code, "nope").getStatusCode());
        }
        assertEquals(java.util.List.of(400, 400, 400, 400, 400, 400, 400, 400, 429), statuses);
    }

    @Test
    void the_invite_page_sends_no_referrer() {
        Response invite = given().when().get("/invite");
        assertEquals("no-referrer", invite.getHeader("Referrer-Policy"));
        assertNull(given().when().get("/api/build").getHeader("Referrer-Policy"));
    }

    private Response start(String code, String phone) throws Exception {
        return given().contentType(ContentType.JSON)
                .body(json.writeValueAsString(Map.of("code", code, "phone", phone, "wording", WORDING)))
                .post("/api/invites/redeem/start");
    }

    private Response resend(String code) throws Exception {
        return given().contentType(ContentType.JSON)
                .body(json.writeValueAsString(Map.of("code", code)))
                .post("/api/invites/redeem/resend");
    }

    private String invite(String name) throws Exception {
        Response created = csrf().body(json.writeValueAsString(Map.of("name", name))).post("/api/invites");
        created.then().statusCode(201);
        String link = created.path("link");
        return link.substring(link.indexOf('#') + 1);
    }

    private io.restassured.specification.RequestSpecification csrf() {
        Response get = given().when().get("/api/invites");
        get.then().statusCode(200);
        return given().contentType(ContentType.JSON)
                .cookie(CsrfFilter.COOKIE, get.cookie(CsrfFilter.COOKIE))
                .header(CsrfFilter.HEADER, get.path("csrfToken"));
    }
}
