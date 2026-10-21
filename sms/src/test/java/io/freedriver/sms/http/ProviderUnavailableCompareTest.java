package io.freedriver.sms.http;

import io.freedriver.sms.otp.FakeSmsSender;
import io.freedriver.sms.otp.ProviderOutage;
import io.freedriver.sms.phones.PhoneDirectory;
import io.freedriver.sms.phones.PhoneEntry;
import io.freedriver.sms.security.PhoneRateLimiter;
import io.freedriver.sms.support.TestNumbers;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import static io.freedriver.sms.support.SmsCalls.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When the provider errors or times out, a listed number and an unlisted number get the same
 * status, body, and headers. The unlisted number is not sent, and it waits as long as the
 * listed number's failed call. An answer from the provider does not add that wait.
 */
@QuarkusTest
class ProviderUnavailableCompareTest {

    private static final String CODE = "123456";
    private static final Duration ERROR_DELAY = Duration.ofMillis(350);
    private static final Duration TIMEOUT_DELAY = Duration.ofMillis(500);
    private static final long SLACK_NANOS = 80_000_000L;

    @Inject
    PhoneDirectory directory;
    @Inject
    FakeSmsSender sender;
    @Inject
    PhoneRateLimiter limiter;
    @Inject
    ProviderOutage outage;

    @BeforeEach
    void reset() {
        sender.reset();
        limiter.reset();
        outage.reset();
    }

    @Test
    void provider_error_matches_for_listed_and_unlisted() {
        compareFailure(ERROR_DELAY, true);
    }

    @Test
    void provider_timeout_matches_for_listed_and_unlisted() {
        compareFailure(TIMEOUT_DELAY, false);
    }

    @Test
    void an_answer_from_the_provider_does_not_add_the_wait() {
        String listed = listed();
        sender.failChecksAfter(ERROR_DELAY);
        verify(listed, CODE).statusCode(503);

        sender.reset();
        long answered = elapsedNanos(() -> verify(listed, CODE).statusCode(400).body("error", equalToInvalid()));
        long unlistedWait = elapsedNanos(() -> {
            Response response = verify(TestNumbers.fresh(), CODE).extract().response();
            assertEquals(400, response.statusCode());
            assertEquals("invalid-code", response.jsonPath().getString("error"));
        });

        assertTrue(answered < 150_000_000L, "answering call waited " + answered);
        assertTrue(unlistedWait < 150_000_000L, "unlisted call waited " + unlistedWait);
    }

    private void compareFailure(Duration delay, boolean error) {
        String listed = listed();
        String unlisted = TestNumbers.fresh();
        if (error) {
            sender.failChecksAfter(delay);
        } else {
            sender.timeOutChecksAfter(delay);
        }
        // The first unavailable response loads the failure path. Measure the pair after that.
        verify(listed(), CODE).statusCode(503);
        outage.reset();

        Response[] listedResponse = new Response[1];
        long listedWait = elapsedNanos(() -> listedResponse[0] = verify(listed, CODE).extract().response());
        Response[] unlistedResponse = new Response[1];
        long unlistedWait = elapsedNanos(() -> unlistedResponse[0] = verify(unlisted, CODE).extract().response());

        assertEquals(503, listedResponse[0].statusCode());
        assertEquals(503, unlistedResponse[0].statusCode());
        assertEquals("unavailable", listedResponse[0].jsonPath().getString("error"));
        assertEquals(listedResponse[0].statusCode(), unlistedResponse[0].statusCode());
        assertEquals(listedResponse[0].asString(), unlistedResponse[0].asString());
        assertEquals(headers(listedResponse[0]), headers(unlistedResponse[0]));
        assertEquals(1, sender.checksFor(listed));
        assertEquals(0, sender.checksFor(unlisted));
        assertTrue(unlistedWait + SLACK_NANOS >= listedWait,
                "unlisted " + unlistedWait + " returned without the listed failure wait " + listedWait);
        assertTrue(listedWait >= delay.toNanos() - SLACK_NANOS, "listed call did not wait for the provider");
    }

    private String listed() {
        String phone = TestNumbers.fresh();
        directory.add(new PhoneEntry(phone, "Mom", "house.user", "dashboard", Instant.now()));
        return phone;
    }

    private static long elapsedNanos(Runnable call) {
        long started = System.nanoTime();
        call.run();
        return System.nanoTime() - started;
    }

    private static Map<String, String> headers(Response response) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        response.getHeaders().forEach(header -> headers.merge(header.getName(), header.getValue(), (a, b) -> a + ", " + b));
        return headers;
    }

    private static org.hamcrest.Matcher<String> equalToInvalid() {
        return org.hamcrest.Matchers.equalTo("invalid-code");
    }
}
