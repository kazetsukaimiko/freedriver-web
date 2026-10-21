package io.freedriver.sms.http;

import io.freedriver.sms.otp.FakeSmsSender;
import io.freedriver.sms.phones.ConsentLedger;
import io.freedriver.sms.phones.ConsentPurpose;
import io.freedriver.sms.phones.ConsentRecord;
import io.freedriver.sms.phones.ConsentSource;
import io.freedriver.sms.phones.InviteWording;
import io.freedriver.sms.phones.PhoneDirectory;
import io.freedriver.sms.phones.PhoneEntry;
import io.freedriver.sms.security.SharedSecretFilter;
import io.freedriver.sms.support.TestNumbers;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static io.freedriver.sms.support.SmsCalls.SECRET;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class InviteCodeResourceTest {

    @Inject
    FakeSmsSender sender;
    @Inject
    ConsentLedger consents;
    @Inject
    PhoneDirectory directory;

    private final java.util.List<String> phones = new java.util.ArrayList<>();

    @AfterEach
    void removePhones() {
        for (String phone : phones) {
            directory.remove(phone);
        }
        phones.clear();
    }

    private String phone() {
        String phone = TestNumbers.fresh();
        phones.add(phone);
        return phone;
    }

    @Test
    void confirm_stores_the_agreement_and_a_later_code_checks() {
        String phone = phone();
        String invite = UUID.randomUUID().toString();
        send(phone, InviteWording.TEXT, invite).statusCode(200).body("sent.phone", is(phone));

        ConsentRecord record = consents.history(phone).getFirst();
        assertEquals(InviteWording.TEXT, record.wording());
        assertEquals(ConsentSource.INVITE_PAGE, record.source());
        assertEquals(ConsentPurpose.SIGN_IN_CODES, record.purpose());
        assertEquals(invite, record.inviteId());
        assertTrue(directory.find(phone).isEmpty());

        String code = sender.pendingCode(phone).orElseThrow();
        given().header(SharedSecretFilter.HEADER, SECRET).contentType("application/json")
                .body(Map.of("phone", phone, "code", "000000"))
                .post("/sms/invite/verify")
                .then().statusCode(400).body("error", is("invalid-code"));
        given().header(SharedSecretFilter.HEADER, SECRET).contentType("application/json")
                .body(Map.of("phone", phone, "code", code))
                .post("/sms/invite/verify")
                .then().statusCode(200).body("verified.phone", is(phone));
        assertTrue(directory.find(phone).isEmpty());
    }

    @Test
    void a_listed_number_is_not_texted_and_other_wording_is_refused() {
        String listed = phone();
        directory.add(new PhoneEntry(listed, "Mom", "house.user", "dashboard", Instant.now()));
        send(listed, InviteWording.TEXT, UUID.randomUUID().toString()).statusCode(409).body("error", is("already-listed"));
        assertTrue(sender.pendingCode(listed).isEmpty());
        assertTrue(consents.history(listed).isEmpty());

        String phone = phone();
        send(phone, "other wording", UUID.randomUUID().toString()).statusCode(400).body("error", is("bad-request"));
        assertTrue(consents.history(phone).isEmpty());
        assertTrue(sender.pendingCode(phone).isEmpty());
    }

    @Test
    void add_accepts_dashboard_only() {
        String phone = phone();
        String username = "inv" + "a".repeat(32);
        given().header(SharedSecretFilter.HEADER, SECRET).contentType("application/json")
                .body(Map.of("phone", phone, "name", "Mom", "username", username, "role", "dashboard"))
                .post("/sms/phones/add")
                .then().statusCode(200).body("added", is(true));
        assertEquals("Mom", directory.find(phone).orElseThrow().name());
        assertEquals("dashboard", directory.find(phone).orElseThrow().role());

        given().header(SharedSecretFilter.HEADER, SECRET).contentType("application/json")
                .body(Map.of("phone", phone, "name", "Mom", "username", username, "role", "portal-admin"))
                .post("/sms/phones/add")
                .then().statusCode(400).body("error", is("bad-request"));
    }

    private static io.restassured.response.ValidatableResponse send(String phone, String wording, String invite) {
        return given().header(SharedSecretFilter.HEADER, SECRET).contentType("application/json")
                .body(Map.of("phone", phone, "wording", wording, "inviteId", invite))
                .post("/sms/invite/send")
                .then();
    }
}
