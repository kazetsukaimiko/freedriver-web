package io.freedriver.sms.api;

import io.freedriver.sms.otp.FakeSmsSender;
import io.freedriver.sms.phones.ConsentLedger;
import io.freedriver.sms.phones.ConsentPurpose;
import io.freedriver.sms.phones.ConsentRecord;
import io.freedriver.sms.phones.ConsentSource;
import io.freedriver.sms.phones.PhoneDirectory;
import io.freedriver.sms.phones.PhoneEntry;
import io.freedriver.sms.security.DailySendCap;
import io.freedriver.sms.security.PhoneRateLimiter;
import io.freedriver.sms.security.SharedSecretFilter;
import io.freedriver.sms.support.LogCapture;
import io.freedriver.sms.support.SmsCalls;
import io.freedriver.sms.support.TestNumbers;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.freedriver.sms.support.SmsCalls.send;
import static io.freedriver.sms.support.SmsCalls.verify;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The HTTP contract Keycloak uses, with the fake sender. */
@QuarkusTest
class OtpResourceTest {

    static final String SECRET = SmsCalls.SECRET;

    @Inject
    PhoneDirectory directory;
    @Inject
    ConsentLedger consents;
    @Inject
    FakeSmsSender sender;
    @Inject
    PhoneRateLimiter limiter;
    @Inject
    DailySendCap dailyCap;

    @BeforeEach
    void reset() {
        sender.reset();
        limiter.reset();
        dailyCap.reset();
    }

    private String listed(String username) {
        String phone = TestNumbers.fresh();
        directory.add(new PhoneEntry(phone, "Mom", username, "dashboard", Instant.now()));
        return phone;
    }

    private String listedAndAgreed(String username) {
        String phone = listed(username);
        consents.record(new ConsentRecord(phone, ConsentPurpose.SIGN_IN_CODES, TestNumbers.WORDING,
                ConsentSource.INVITE_PAGE, Instant.now()));
        return phone;
    }

    @Test
    void missing_or_wrong_secret_is_401_and_reaches_nothing() {
        String phone = listedAndAgreed("house.user");
        given().contentType(ContentType.JSON).body(Map.of("phone", phone)).post("/otp/send")
                .then().statusCode(401).body("error", equalTo("unauthorized"));
        given().header(SharedSecretFilter.HEADER, "wrong").contentType(ContentType.JSON)
                .body(Map.of("phone", phone)).post("/otp/send").then().statusCode(401);
        given().header(SharedSecretFilter.HEADER, "wrong").get("/otp/anything").then().statusCode(401);
        assertEquals(0, sender.deliveries());
    }

    @ParameterizedTest
    @ValueSource(strings = {"+445555550100", "+525555550100", "5555550100", "+1555555010", "+155555501000", "+1 555 555 0100", ""})
    void non_us_numbers_are_refused_by_validation_before_any_sender_call(String phone) {
        try (LogCapture logs = LogCapture.open()) {
            send(phone).statusCode(400).body("error", equalTo("bad-request"));
            verify(phone, "123456").statusCode(400).body("error", equalTo("bad-request"));
            assertFalse(logs.all().contains("agreement"), "the agreement check must not run: " + logs.all());
        }
        assertEquals(0, sender.deliveries());
        assertEquals(0, sender.checks());
    }

    @Test
    void missing_phone_and_malformed_json_are_bad_requests() {
        given().header(SharedSecretFilter.HEADER, SECRET).contentType(ContentType.JSON).body("{}")
                .post("/otp/send").then().statusCode(400);
        given().header(SharedSecretFilter.HEADER, SECRET).contentType(ContentType.JSON).body("{\"phone\":")
                .post("/otp/send").then().statusCode(400);
        assertEquals(0, sender.deliveries());
    }

    @Test
    void unlisted_number_gets_the_same_answer_no_provider_call_and_nothing_stored() {
        String phone = TestNumbers.fresh();
        int listSize = directory.list().size();

        send(phone).statusCode(200).body("status", equalTo("sent"));

        assertEquals(0, sender.deliveries());
        assertTrue(consents.history(phone).isEmpty());
        assertEquals(listSize, directory.list().size());
        verify(phone, "123456").statusCode(400).body("error", equalTo("invalid-code"));
        assertEquals(0, sender.checks());
    }

    @Test
    void listed_number_without_an_agreement_is_refused_masked_and_answers_sent() {
        String phone = listed("house.user");
        try (LogCapture logs = LogCapture.open()) {
            send(phone).statusCode(200).body("status", equalTo("sent"));
            assertTrue(logs.all().contains("***" + phone.substring(phone.length() - 4)), logs.all());
            assertFalse(logs.all().contains(phone), logs.all());
        }
        assertEquals(0, sender.deliveries());
    }

    @Test
    void agreed_number_gets_a_code_that_signs_in_once() {
        String phone = listedAndAgreed("house.user");

        send(phone).statusCode(200).body("status", equalTo("sent"));
        assertEquals(1, sender.deliveries());
        String code = sender.pendingCode(phone).orElseThrow();

        verify(phone, code).statusCode(200).body("username", equalTo("house.user"));
        verify(phone, code).statusCode(400).body("error", equalTo("invalid-code"));
    }

    @Test
    void repeat_code_for_a_number_that_already_agreed() {
        String phone = listedAndAgreed("house.user");
        send(phone).statusCode(200);
        send(phone).statusCode(200);
        assertEquals(2, sender.deliveries());
        assertEquals(1, consents.history(phone).size());
    }

    @Test
    void non_numeric_code_is_invalid_without_a_provider_check() {
        String phone = listedAndAgreed("house.user");
        verify(phone, "abc").statusCode(400).body("error", equalTo("invalid-code"));
        assertEquals(0, sender.checks());
    }

    @Test
    void five_sends_per_number_then_429_and_other_numbers_unaffected() {
        String phone = listedAndAgreed("house.user");
        for (int i = 0; i < 5; i++) {
            send(phone).statusCode(200);
        }
        send(phone).statusCode(429).body("error", equalTo("rate-limited"));
        assertEquals(5, sender.deliveries());

        String unlisted = TestNumbers.fresh();
        for (int i = 0; i < 5; i++) {
            send(unlisted).statusCode(200);
        }
        send(unlisted).statusCode(429);
        send(listedAndAgreed("other.user")).statusCode(200);
    }

    @Test
    void five_wrong_codes_lock_the_number_even_for_the_right_code() {
        String phone = listedAndAgreed("house.user");
        send(phone).statusCode(200);
        String code = sender.pendingCode(phone).orElseThrow();
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) {
            verify(phone, wrong).statusCode(400).body("error", equalTo("invalid-code"));
        }
        int checks = sender.checks();
        verify(phone, code).statusCode(400).body("error", equalTo("invalid-code"));
        assertEquals(checks, sender.checks(), "a locked number reaches no provider check");
    }

    @Test
    void removing_a_number_keeps_its_agreement_history_and_stops_codes() {
        String phone = listedAndAgreed("house.user");
        assertTrue(directory.remove(phone));

        assertEquals(1, consents.history(phone).size());
        assertEquals(TestNumbers.WORDING, consents.history(phone).getFirst().wording());
        send(phone).statusCode(200).body("status", equalTo("sent"));
        assertEquals(0, sender.deliveries());
    }

    @Test
    void health_live_and_ready_are_open_and_up() {
        given().get("/health/live").then().statusCode(200);
        given().get("/health").then().statusCode(200);
        given().get("/health/ready").then().statusCode(200);
    }

    @Test
    void responses_are_not_cacheable() {
        send(TestNumbers.fresh()).header("Cache-Control", equalTo("no-store"));
    }
}
