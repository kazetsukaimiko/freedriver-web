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
import io.freedriver.sms.support.LogCapture;
import io.freedriver.sms.support.SmsCalls;
import io.freedriver.sms.support.TestNumbers;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The daily cap counts only sends that reach the provider: listed numbers with an agreement on file. */
@QuarkusTest
@TestProfile(DailySendCapTest.SmallCap.class)
class DailySendCapTest {

    static final int CAP = 3;

    public static class SmallCap implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("freedriver.sms.daily-send-cap", String.valueOf(CAP));
        }
    }

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

    private String listed() {
        String phone = TestNumbers.fresh();
        directory.add(new PhoneEntry(phone, "Guest", "guest.user", "dashboard", Instant.now()));
        return phone;
    }

    private String agreed() {
        String phone = listed();
        consents.record(new ConsentRecord(phone, ConsentPurpose.SIGN_IN_CODES, TestNumbers.WORDING,
                ConsentSource.INVITE_PAGE, Instant.now()));
        return phone;
    }

    private static ExtractableResponse<Response> raw(String phone) {
        return SmsCalls.send(phone).extract();
    }

    private static void assertSameAnswer(ExtractableResponse<Response> expected, ExtractableResponse<Response> actual) {
        assertEquals(expected.statusCode(), actual.statusCode());
        assertArrayEquals(expected.asByteArray(), actual.asByteArray());
        assertEquals(expected.header("Content-Type"), actual.header("Content-Type"));
        assertEquals(expected.header("Content-Length"), actual.header("Content-Length"));
        assertEquals(expected.header("Cache-Control"), actual.header("Cache-Control"));
        assertEquals(headerNames(expected), headerNames(actual));
    }

    private static java.util.Set<String> headerNames(ExtractableResponse<Response> response) {
        return response.headers().asList().stream().map(h -> h.getName().toLowerCase())
                .filter(n -> !n.equals("date")).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void unlisted_numbers_beyond_the_cap_do_not_use_it_up() {
        for (int i = 0; i < CAP * 3; i++) {
            SmsCalls.send(TestNumbers.fresh()).statusCode(200).body("status", equalTo("sent"));
        }
        assertEquals(0, sender.deliveries());

        SmsCalls.send(agreed()).statusCode(200).body("status", equalTo("sent"));
        assertEquals(1, sender.deliveries(), "a listed number with an agreement still gets a real send");
    }

    @Test
    void listed_numbers_without_an_agreement_do_not_count() {
        for (int i = 0; i < CAP * 3; i++) {
            SmsCalls.send(listed()).statusCode(200).body("status", equalTo("sent"));
        }
        assertEquals(0, sender.deliveries());

        for (int i = 0; i < CAP; i++) {
            SmsCalls.send(agreed()).statusCode(200);
        }
        assertEquals(CAP, sender.deliveries());
    }

    @Test
    void listed_and_agreed_sends_count_and_the_cap_answers_like_an_unlisted_number() {
        for (int i = 0; i < CAP; i++) {
            SmsCalls.send(agreed()).statusCode(200).body("status", equalTo("sent"));
        }
        assertEquals(CAP, sender.deliveries());

        ExtractableResponse<Response> unlistedBefore = raw(TestNumbers.fresh());
        try (LogCapture logs = LogCapture.open()) {
            ExtractableResponse<Response> capped = raw(agreed());
            ExtractableResponse<Response> unlisted = raw(TestNumbers.fresh());
            ExtractableResponse<Response> listedOnly = raw(listed());

            assertEquals(200, capped.statusCode());
            assertSameAnswer(unlisted, capped);
            assertSameAnswer(unlisted, listedOnly);
            assertSameAnswer(unlistedBefore, capped);
            assertTrue(logs.lines().stream()
                            .anyMatch(l -> (l.startsWith("ERROR") || l.startsWith("SEVERE")) && l.contains("daily send cap")),
                    logs.all());
        }
        assertEquals(CAP, sender.deliveries(), "no provider call once the cap is reached");
    }

    @Test
    void per_number_limit_is_unchanged_under_the_cap() {
        String phone = TestNumbers.fresh();
        for (int i = 0; i < 5; i++) {
            SmsCalls.send(phone).statusCode(200);
        }
        SmsCalls.send(phone).statusCode(429).body("error", equalTo("rate-limited"));
    }
}
