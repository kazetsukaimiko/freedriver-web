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
import io.freedriver.sms.support.SameAnswer;
import io.restassured.http.ContentType;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
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

/** The HTTP contract of {@code /sms/send} and {@code /sms/verify}, with the fake sender. */
@QuarkusTest
class SmsResourceTest {

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
        given().contentType(ContentType.JSON).body(Map.of("type", "otp", "phone", phone)).post("/sms/send")
                .then().statusCode(401).body("error", equalTo("unauthorized"));
        given().header(SharedSecretFilter.HEADER, "wrong").contentType(ContentType.JSON)
                .body(Map.of("type", "otp", "phone", phone)).post("/sms/send").then().statusCode(401);
        given().header(SharedSecretFilter.HEADER, "wrong").contentType(ContentType.JSON)
                .body(Map.of("phone", phone, "code", "123456")).post("/sms/verify").then().statusCode(401);
        given().header(SharedSecretFilter.HEADER, "wrong").get("/sms/anything").then().statusCode(401);
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

    private static ValidatableResponse sendRaw(String body) {
        return given().header(SharedSecretFilter.HEADER, SECRET).contentType(ContentType.JSON).body(body)
                .post("/sms/send").then();
    }

    @Test
    void missing_phone_and_malformed_json_are_bad_requests() {
        sendRaw("{\"type\":\"otp\"}").statusCode(400);
        sendRaw("{\"type\":\"otp\",\"phone\":").statusCode(400);
        sendRaw("{}").statusCode(400);
        assertEquals(0, sender.deliveries());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"phone\":\"+15555559999\"}",
            "{\"type\":\"text\",\"phone\":\"+15555559999\"}",
            "{\"type\":\"OTP\",\"phone\":\"+15555559999\"}",
            "{\"type\":null,\"phone\":\"+15555559999\"}",
            "{\"type\":7,\"phone\":\"+15555559999\"}"})
    void missing_or_unknown_type_is_400_and_counts_nothing(String body) {
        for (int i = 0; i < 6; i++) {
            sendRaw(body).statusCode(400);
        }
        assertEquals(0, sender.deliveries());
        send("+15555559999").statusCode(200);
    }

    @Test
    void send_answers_with_the_request_type_and_number() {
        String phone = listedAndAgreed("house.user");
        String unlisted = TestNumbers.fresh();
        assertEquals("{\"sent\":{\"type\":\"otp\",\"phone\":\"" + phone + "\"}}",
                send(phone).statusCode(200).extract().asString());
        assertEquals("{\"sent\":{\"type\":\"otp\",\"phone\":\"" + unlisted + "\"}}",
                send(unlisted).statusCode(200).extract().asString());
    }

    @Test
    void verify_answers_with_the_request_type_and_number() {
        String phone = listedAndAgreed("house.user");
        send(phone).statusCode(200);
        String code = sender.pendingCode(phone).orElseThrow();
        assertEquals("{\"verified\":{\"type\":\"otp\",\"phone\":\"" + phone + "\"}}",
                verify(phone, code).statusCode(200).extract().asString());
    }

    @Test
    void unlisted_no_pending_expired_and_wrong_codes_get_one_identical_answer() {
        String phone = TestNumbers.fresh();
        ExtractableResponse<Response> unlisted = verify(phone, "123456").extract();

        directory.add(new PhoneEntry(phone, "Mom", "house.user", "dashboard", Instant.now()));
        consents.record(new ConsentRecord(phone, ConsentPurpose.SIGN_IN_CODES, TestNumbers.WORDING,
                ConsentSource.INVITE_PAGE, Instant.now()));
        ExtractableResponse<Response> noPending = verify(phone, "123456").extract();

        send(phone).statusCode(200);
        String expiredCode = sender.pendingCode(phone).orElseThrow();
        sender.expirePending(phone);
        ExtractableResponse<Response> expired = verify(phone, expiredCode).extract();

        send(phone).statusCode(200);
        String code = sender.pendingCode(phone).orElseThrow();
        ExtractableResponse<Response> wrong = verify(phone, code.equals("000000") ? "111111" : "000000").extract();

        assertEquals(400, unlisted.statusCode());
        assertEquals("{\"error\":\"invalid-code\"}", unlisted.asString());
        SameAnswer.assertSameAnswer(unlisted, noPending);
        SameAnswer.assertSameAnswer(unlisted, expired);
        SameAnswer.assertSameAnswer(unlisted, wrong);
    }

    @Test
    void past_the_send_limit_listed_and_unlisted_numbers_get_the_same_answer() {
        String listed = listedAndAgreed("house.user");
        String unlisted = TestNumbers.fresh();
        for (int i = 0; i < 5; i++) {
            send(listed).statusCode(200);
            send(unlisted).statusCode(200);
        }
        ExtractableResponse<Response> listedLimited = send(listed).extract();
        ExtractableResponse<Response> unlistedLimited = send(unlisted).extract();

        assertEquals(429, listedLimited.statusCode());
        assertEquals("900", listedLimited.header("Retry-After"));
        assertEquals("{\"error\":\"rate-limited\"}", listedLimited.asString());
        SameAnswer.assertSameAnswer(listedLimited, unlistedLimited);
        assertEquals(5, sender.deliveries());
    }

    @Test
    void past_the_wrong_code_limit_listed_and_unlisted_numbers_get_the_same_answer() {
        String listed = listedAndAgreed("house.user");
        String unlisted = TestNumbers.fresh();
        send(listed).statusCode(200);
        String code = sender.pendingCode(listed).orElseThrow();
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) {
            verify(listed, wrong).statusCode(400);
            verify(unlisted, wrong).statusCode(400);
        }
        int checks = sender.checks();
        ExtractableResponse<Response> listedLimited = verify(listed, code).extract();
        ExtractableResponse<Response> unlistedLimited = verify(unlisted, code).extract();

        assertEquals(429, listedLimited.statusCode());
        assertEquals("900", listedLimited.header("Retry-After"));
        SameAnswer.assertSameAnswer(listedLimited, unlistedLimited);
        assertEquals(checks, sender.checks(), "a locked number reaches no provider check");
    }

    @Test
    void unlisted_number_gets_the_same_answer_no_provider_call_and_nothing_stored() {
        String phone = TestNumbers.fresh();
        int listSize = directory.list().size();

        send(phone).statusCode(200).body("sent.type", equalTo("otp"));

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
            send(phone).statusCode(200).body("sent.type", equalTo("otp"));
            assertTrue(logs.all().contains("***" + phone.substring(phone.length() - 2)), logs.all());
            assertFalse(logs.all().contains(phone), logs.all());
        }
        assertEquals(0, sender.deliveries());
    }

    @Test
    void agreed_number_gets_a_code_that_signs_in_once() {
        String phone = listedAndAgreed("house.user");

        send(phone).statusCode(200).body("sent.type", equalTo("otp"));
        assertEquals(1, sender.deliveries());
        String code = sender.pendingCode(phone).orElseThrow();

        verify(phone, code).statusCode(200).body("verified.type", equalTo("otp")).body("verified.phone", equalTo(phone));
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
        verify(phone, code).statusCode(429).body("error", equalTo("rate-limited"));
        assertEquals(checks, sender.checks(), "a locked number reaches no provider check");
    }

    @Test
    void removing_a_number_keeps_its_agreement_history_and_stops_codes() {
        String phone = listedAndAgreed("house.user");
        assertTrue(directory.remove(phone));

        assertEquals(1, consents.history(phone).size());
        assertEquals(TestNumbers.WORDING, consents.history(phone).getFirst().wording());
        send(phone).statusCode(200).body("sent.type", equalTo("otp"));
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
