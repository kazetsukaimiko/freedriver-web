package io.freedriver.sms.twilio;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import io.freedriver.sms.otp.ActiveSender;
import io.freedriver.sms.phones.ConsentLedger;
import io.freedriver.sms.phones.ConsentPurpose;
import io.freedriver.sms.phones.ConsentRecord;
import io.freedriver.sms.phones.ConsentSource;
import io.freedriver.sms.phones.PhoneDirectory;
import io.freedriver.sms.phones.PhoneEntry;
import io.freedriver.sms.security.DailySendCap;
import io.freedriver.sms.security.PhoneRateLimiter;
import io.freedriver.sms.support.SameAnswer;
import io.freedriver.sms.support.SmsCalls;
import io.freedriver.sms.support.TestNumbers;
import io.freedriver.sms.support.TwilioStubResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The running service with the Twilio sender against a fake Verify endpoint. */
@QuarkusTest
@TestProfile(TwilioEndToEndTest.TwilioProfile.class)
class TwilioEndToEndTest {

    private static final String VERIFICATIONS = "/v2/Services/" + TestNumbers.SID_SERVICE + "/Verifications";
    private static final String CHECK = "/v2/Services/" + TestNumbers.SID_SERVICE + "/VerificationCheck";

    public static class TwilioProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "freedriver.sms.sender", "twilio",
                    "freedriver.sms.daily-send-cap", "2",
                    TwilioCredentials.ACCOUNT_SID, TestNumbers.SID_ACCOUNT,
                    TwilioCredentials.API_KEY_SID, TestNumbers.SID_API_KEY,
                    TwilioCredentials.API_KEY_SECRET, TestNumbers.API_KEY_SECRET,
                    TwilioCredentials.VERIFY_SERVICE_SID, TestNumbers.SID_SERVICE);
        }

        @Override
        public List<TestResourceEntry> testResources() {
            return List.of(new TestResourceEntry(TwilioStubResource.class));
        }
    }

    @Inject
    PhoneDirectory directory;
    @Inject
    ConsentLedger consents;
    @Inject
    ActiveSender active;
    @Inject
    PhoneRateLimiter limiter;
    @Inject
    DailySendCap dailyCap;

    WireMockServer twilio;

    @BeforeEach
    void reset() {
        twilio = TwilioStubResource.server();
        twilio.resetAll();
        twilio.stubFor(post(urlEqualTo(VERIFICATIONS)).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody("{\"status\":\"pending\"}")));
        twilio.stubFor(post(urlEqualTo(CHECK)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"status\":\"approved\"}")));
        limiter.reset();
        dailyCap.reset();
    }

    private String agreed() {
        String phone = TestNumbers.fresh();
        directory.add(new PhoneEntry(phone, "Mom", "house.user", "dashboard", Instant.now()));
        consents.record(new ConsentRecord(phone, ConsentPurpose.SIGN_IN_CODES, TestNumbers.WORDING,
                ConsentSource.INVITE_PAGE, Instant.now()));
        return phone;
    }

    @Test
    void twilio_sender_is_active_and_ready() {
        assertInstanceOf(TwilioVerifySender.class, active.get());
        given().get("/health").then().statusCode(200);
        given().get("/health/live").then().statusCode(200);
    }

    @Test
    void non_us_number_is_refused_before_any_twilio_call() {
        SmsCalls.send("+445555550100").statusCode(400);
        SmsCalls.verify("+445555550100", "123456").statusCode(400);
        assertEquals(0, twilio.getAllServeEvents().size());
    }

    @Test
    void sign_in_uses_only_create_verification_and_create_check() {
        String phone = agreed();
        SmsCalls.send(phone).statusCode(200).body("sent.type", equalTo("otp"));
        SmsCalls.verify(phone, "123456").statusCode(200).body("verified.type", equalTo("otp")).body("verified.phone", equalTo(phone));

        assertEquals(2, twilio.getAllServeEvents().size());
        for (ServeEvent event : twilio.getAllServeEvents()) {
            assertEquals("POST", event.getRequest().getMethod().getName());
            assertTrue(Set.of(VERIFICATIONS, CHECK).contains(event.getRequest().getUrl()));
        }
    }

    @Test
    void unlisted_and_unagreed_numbers_never_reach_twilio() {
        SmsCalls.send(TestNumbers.fresh()).statusCode(200).body("sent.type", equalTo("otp"));
        String listedOnly = TestNumbers.fresh();
        directory.add(new PhoneEntry(listedOnly, "Dad", "dad.user", "dashboard", Instant.now()));
        SmsCalls.send(listedOnly).statusCode(200).body("sent.type", equalTo("otp"));
        assertEquals(0, twilio.getAllServeEvents().size());
    }

    @Test
    void daily_cap_counts_only_twilio_sends_and_answers_like_an_unlisted_number() {
        for (int i = 0; i < 4; i++) {
            SmsCalls.send(TestNumbers.fresh()).statusCode(200);
        }
        SmsCalls.send(agreed()).statusCode(200);
        SmsCalls.send(agreed()).statusCode(200);
        assertEquals(2, twilio.getAllServeEvents().size());

        String phone = TestNumbers.fresh();
        var unlisted = SmsCalls.send(phone).extract();
        directory.add(new PhoneEntry(phone, "Mom", "house.user", "dashboard", Instant.now()));
        consents.record(new ConsentRecord(phone, ConsentPurpose.SIGN_IN_CODES, TestNumbers.WORDING,
                ConsentSource.INVITE_PAGE, Instant.now()));
        var capped = SmsCalls.send(phone).extract();

        assertEquals(200, capped.statusCode());
        SameAnswer.assertSameAnswer(unlisted, capped);
        assertEquals(2, twilio.getAllServeEvents().size(), "no Twilio call once the cap is reached");
    }

    @Test
    void twilio_failure_still_answers_sent() {
        twilio.stubFor(post(urlEqualTo(VERIFICATIONS)).willReturn(aResponse().withStatus(503).withBody("{\"code\":20503}")));
        SmsCalls.send(agreed()).statusCode(200).body("sent.type", equalTo("otp"));
    }
}
