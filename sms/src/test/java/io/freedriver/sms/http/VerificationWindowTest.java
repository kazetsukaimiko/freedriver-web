package io.freedriver.sms.http;

import io.freedriver.sms.otp.CheckOutcome;
import io.freedriver.sms.otp.FakeSmsSender;
import io.freedriver.sms.otp.VerificationLedger;
import io.freedriver.sms.phones.ConsentLedger;
import io.freedriver.sms.phones.ConsentPurpose;
import io.freedriver.sms.phones.ConsentRecord;
import io.freedriver.sms.phones.ConsentSource;
import io.freedriver.sms.phones.PhoneDirectory;
import io.freedriver.sms.phones.PhoneEntry;
import io.freedriver.sms.security.DailySendCap;
import io.freedriver.sms.security.PhoneRateLimiter;
import io.freedriver.sms.support.MutableClock;
import io.freedriver.sms.support.SameAnswer;
import io.freedriver.sms.support.TestNumbers;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static io.freedriver.sms.support.SmsCalls.send;
import static io.freedriver.sms.support.SmsCalls.verify;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The 5-minute expiry and the fifth-attempt cap, with the fake sender and a clock the test moves.
 * Each reason is the same status, body and headers for a listed number and an unlisted number.
 */
@QuarkusTest
class VerificationWindowTest {

    private static final Instant START = Instant.parse("2026-06-01T00:00:00Z");
    private static final Duration FOUR_FIFTY_NINE = Duration.ofMinutes(4).plusSeconds(59);
    private static final Duration FIVE_OH_ONE = Duration.ofMinutes(5).plusSeconds(1);

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
    @Inject
    VerificationLedger ledger;

    private final MutableClock clock = new MutableClock(START);

    @BeforeEach
    void reset() {
        sender.reset();
        limiter.reset();
        dailyCap.reset();
        ledger.reset();
        clockAt(START);
        ledger.setClock(clock);
    }

    @AfterEach
    void restoreClock() {
        ledger.reset();
    }

    @Test
    void verify_at_4_59_checks_the_code_and_at_5_01_is_expired_without_a_check() {
        String phone = listedAndAgreed();
        send(phone).statusCode(200);
        String code = sender.pendingCode(phone).orElseThrow();
        clock.advance(FOUR_FIFTY_NINE);

        verify(phone, code).statusCode(200).body("verified.phone", equalTo(phone));
        assertEquals(1, sender.checks());

        send(phone).statusCode(200);
        String later = sender.pendingCode(phone).orElseThrow();
        int checks = sender.checks();
        int cancels = sender.cancelledSids().size();
        clock.advance(FIVE_OH_ONE);

        verify(phone, later).statusCode(400)
                .body("error", equalTo("invalid-code"))
                .body("reason", equalTo("expired"))
                .body("type", equalTo("otp"));
        assertEquals(checks, sender.checks(), "an expired code is not sent to the provider");
        assertEquals(cancels + 1, sender.cancelledSids().size());
    }

    @Test
    void resend_within_10_minutes_cancels_the_first_verification() {
        String phone = listedAndAgreed();
        send(phone).statusCode(200);
        String first = sender.pendingSid(phone).orElseThrow();
        clock.advance(Duration.ofMinutes(9));

        send(phone).statusCode(200);

        assertEquals(List.of(first), sender.cancelledSids());
        assertFalse(first.equals(sender.pendingSid(phone).orElseThrow()));
        assertEquals(2, sender.deliveries());
        assertEquals(0, sender.checks());
    }

    @Test
    void sixth_attempt_is_429_and_matches_for_listed_and_unlisted() {
        String listed = listedAndAgreed();
        String unlisted = TestNumbers.fresh();
        send(listed).statusCode(200);
        send(unlisted).statusCode(200);
        String code = sender.pendingCode(listed).orElseThrow();
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) {
            verify(listed, wrong).statusCode(400).body("reason", equalTo("wrong_code"));
            verify(unlisted, wrong).statusCode(400).body("reason", equalTo("wrong_code"));
        }
        int checks = sender.checks();

        ExtractableResponse<Response> listedLimited = verify(listed, code).extract();
        ExtractableResponse<Response> unlistedLimited = verify(unlisted, wrong).extract();

        assertEquals(429, listedLimited.statusCode());
        assertEquals("900", listedLimited.header("Retry-After"));
        assertEquals("{\"error\":\"rate-limited\",\"reason\":\"too_many_attempts\",\"type\":\"otp\"}",
                listedLimited.asString());
        SameAnswer.assertSameAnswer(listedLimited, unlistedLimited);
        assertEquals(checks, sender.checks(), "the sixth attempt reaches no provider check");
        assertEquals(5, sender.checksFor(listed));
        assertEquals(0, sender.checksFor(unlisted));
    }

    @Test
    void expired_matches_for_listed_and_unlisted_and_wins_over_the_attempt_cap() {
        String listed = listedAndAgreed();
        String unlisted = TestNumbers.fresh();
        String none = TestNumbers.fresh();
        send(listed).statusCode(200);
        send(unlisted).statusCode(200);
        String code = sender.pendingCode(listed).orElseThrow();
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) {
            verify(listed, wrong).statusCode(400);
        }
        clock.advance(FIVE_OH_ONE);
        int checks = sender.checks();

        ExtractableResponse<Response> listedExpired = verify(listed, wrong).extract();
        ExtractableResponse<Response> unlistedExpired = verify(unlisted, wrong).extract();
        ExtractableResponse<Response> noPending = verify(none, wrong).extract();

        assertEquals(400, listedExpired.statusCode());
        assertEquals("{\"error\":\"invalid-code\",\"reason\":\"expired\",\"type\":\"otp\"}", listedExpired.asString());
        SameAnswer.assertSameAnswer(listedExpired, unlistedExpired);
        SameAnswer.assertSameAnswer(listedExpired, noPending);
        assertEquals(checks, sender.checks());
        assertEquals(1, sender.cancelledSids().size());
    }

    @Test
    void wrong_code_matches_for_listed_and_unlisted() {
        String listed = listedAndAgreed();
        String unlisted = TestNumbers.fresh();
        send(listed).statusCode(200);
        send(unlisted).statusCode(200);
        String code = sender.pendingCode(listed).orElseThrow();
        String wrong = code.equals("000000") ? "111111" : "000000";

        ExtractableResponse<Response> listedWrong = verify(listed, wrong).extract();
        ExtractableResponse<Response> unlistedWrong = verify(unlisted, wrong).extract();

        assertEquals(400, listedWrong.statusCode());
        assertEquals("{\"error\":\"invalid-code\",\"reason\":\"wrong_code\",\"type\":\"otp\"}", listedWrong.asString());
        SameAnswer.assertSameAnswer(listedWrong, unlistedWrong);
        assertEquals(1, sender.checksFor(listed));
        assertEquals(0, sender.checksFor(unlisted));
    }

    @Test
    void twilio_60202_is_the_same_429_and_the_next_check_does_not_call_the_provider() {
        String phone = listedAndAgreed();
        send(phone).statusCode(200);
        sender.forceNextCheck(CheckOutcome.TOO_MANY_ATTEMPTS);

        verify(phone, "000000").statusCode(429)
                .body("error", equalTo("rate-limited"))
                .body("reason", equalTo("too_many_attempts"))
                .header("Retry-After", equalTo("900"));
        int checks = sender.checks();
        verify(phone, "000000").statusCode(429).body("reason", equalTo("too_many_attempts"));
        assertEquals(checks, sender.checks());
    }

    private void clockAt(Instant instant) {
        clock.advance(Duration.between(clock.instant(), instant));
    }

    private String listedAndAgreed() {
        String phone = TestNumbers.fresh();
        directory.add(new PhoneEntry(phone, "Mom", "house.user", "dashboard", Instant.now()));
        consents.record(new ConsentRecord(phone, ConsentPurpose.SIGN_IN_CODES, TestNumbers.WORDING,
                ConsentSource.INVITE_PAGE, Instant.now()));
        return phone;
    }
}
