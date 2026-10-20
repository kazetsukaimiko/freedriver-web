package io.freedriver.sms.twilio;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import io.freedriver.sms.otp.CheckOutcome;
import io.freedriver.sms.otp.SendOutcome;
import io.freedriver.sms.phones.ConsentLedger;
import io.freedriver.sms.phones.ConsentPurpose;
import io.freedriver.sms.phones.ConsentRecord;
import io.freedriver.sms.phones.ConsentSource;
import io.freedriver.sms.support.Json;
import io.freedriver.sms.support.LogCapture;
import io.freedriver.sms.support.TestNumbers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Twilio client called directly against a fake Verify endpoint. */
class TwilioVerifySenderTest {

    private static final String VERIFICATIONS = "/v2/Services/" + TestNumbers.SID_SERVICE + "/Verifications";
    private static final String CHECK = "/v2/Services/" + TestNumbers.SID_SERVICE + "/VerificationCheck";

    @TempDir
    Path dataDir;

    private WireMockServer twilio;
    private ConsentLedger consents;
    private TwilioVerifySender sender;

    @BeforeEach
    void start() {
        twilio = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        twilio.start();
        consents = new ConsentLedger(dataDir, Json.mapper());
        sender = sender(Duration.ofSeconds(2));
    }

    @AfterEach
    void stop() {
        twilio.stop();
    }

    private TwilioVerifySender sender(Duration timeout) {
        TwilioCredentials credentials = new TwilioCredentials(TestNumbers.SID_ACCOUNT, TestNumbers.SID_API_KEY,
                TestNumbers.API_KEY_SECRET, TestNumbers.SID_SERVICE);
        TwilioEndpoint endpoint = TwilioEndpoint.resolve("http://localhost:" + twilio.port(), true);
        return new TwilioVerifySender(credentials, endpoint, timeout, consents);
    }

    private String agreed() {
        String phone = TestNumbers.fresh();
        consents.record(new ConsentRecord(phone, ConsentPurpose.SIGN_IN_CODES, TestNumbers.WORDING,
                ConsentSource.INVITE_PAGE, Instant.now()));
        return phone;
    }

    private void stubSend(int status, String body) {
        twilio.stubFor(post(urlEqualTo(VERIFICATIONS)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    private void stubCheck(int status, String body) {
        twilio.stubFor(post(urlEqualTo(CHECK)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    void send_creates_a_verification_with_api_key_auth_and_no_custom_code() {
        String phone = agreed();
        stubSend(201, "{\"sid\":\"VE1\",\"status\":\"pending\"}");

        assertEquals(SendOutcome.SENT, sender.sendCode(phone));

        String basic = "Basic " + Base64.getEncoder().encodeToString(
                (TestNumbers.SID_API_KEY + ":" + TestNumbers.API_KEY_SECRET).getBytes(StandardCharsets.UTF_8));
        twilio.verify(1, postRequestedFor(urlEqualTo(VERIFICATIONS))
                .withHeader("Authorization", equalTo(basic))
                .withFormParam("To", equalTo(phone))
                .withFormParam("Channel", equalTo("sms")));
        String body = twilio.getAllServeEvents().getFirst().getRequest().getBodyAsString();
        assertFalse(body.toLowerCase().contains("customcode"), "sms never passes its own code");
        assertEquals(Set.of("To", "Channel"), formKeys(body));
    }

    @Test
    void check_approves_a_matching_code() {
        String phone = agreed();
        stubCheck(200, "{\"status\":\"approved\",\"valid\":true}");

        assertEquals(CheckOutcome.APPROVED, sender.checkCode(phone, "123456"));
        twilio.verify(1, postRequestedFor(urlEqualTo(CHECK))
                .withFormParam("To", equalTo(phone))
                .withFormParam("Code", equalTo("123456")));
    }

    @Test
    void check_reports_a_wrong_code_from_twilio() {
        String phone = agreed();
        stubCheck(200, "{\"status\":\"pending\",\"valid\":false}");
        assertEquals(CheckOutcome.WRONG_CODE, sender.checkCode(phone, "000000"));
    }

    @Test
    void check_without_an_open_verification_is_a_wrong_code() {
        String phone = agreed();
        stubCheck(404, "{\"code\":20404,\"message\":\"not found\"}");
        assertEquals(CheckOutcome.WRONG_CODE, sender.checkCode(phone, "000000"));
    }

    @Test
    void refused_send_is_logged_with_the_number_masked() {
        String phone = agreed();
        stubSend(400, "{\"code\":60200,\"message\":\"Invalid parameter `To`: " + phone + "\"}");

        try (LogCapture logs = LogCapture.open()) {
            assertEquals(SendOutcome.FAILED, sender.sendCode(phone));
            String out = logs.all();
            assertTrue(out.contains("***" + phone.substring(phone.length() - 2)), out);
            assertTrue(out.contains("60200"), out);
            assertFalse(out.contains(phone), "full number must not be logged: " + out);
            assertFalse(out.contains(phone.substring(2)), "full number must not be logged: " + out);
        }
    }

    @Test
    void timeout_is_logged_with_the_number_masked() {
        String phone = agreed();
        twilio.stubFor(post(urlEqualTo(VERIFICATIONS)).willReturn(aResponse().withStatus(201)
                .withFixedDelay(1500).withBody("{}")));
        TwilioVerifySender impatient = sender(Duration.ofMillis(300));

        try (LogCapture logs = LogCapture.open()) {
            assertEquals(SendOutcome.FAILED, impatient.sendCode(phone));
            String out = logs.all();
            assertTrue(out.contains("timed out"), out);
            assertTrue(out.contains("***" + phone.substring(phone.length() - 2)), out);
            assertFalse(out.contains(phone), out);
        }
    }

    @Test
    void check_timeout_fails_without_logging_the_code() {
        String phone = agreed();
        twilio.stubFor(post(urlEqualTo(CHECK)).willReturn(aResponse().withStatus(200)
                .withFixedDelay(1500).withBody("{\"status\":\"approved\"}")));
        TwilioVerifySender impatient = sender(Duration.ofMillis(300));

        try (LogCapture logs = LogCapture.open()) {
            assertEquals(CheckOutcome.FAILED, impatient.checkCode(phone, "482913"));
            assertFalse(logs.all().contains("482913"), "codes are never logged");
            assertFalse(logs.all().contains(phone), "full number must not be logged");
        }
    }

    @Test
    void number_without_an_agreement_is_refused_before_any_twilio_call() {
        String phone = TestNumbers.fresh();
        stubSend(201, "{\"status\":\"pending\"}");

        try (LogCapture logs = LogCapture.open()) {
            assertEquals(SendOutcome.REFUSED_NO_AGREEMENT, sender.sendCode(phone));
            assertTrue(logs.all().contains("***" + phone.substring(phone.length() - 2)), logs.all());
            assertFalse(logs.all().contains(phone), logs.all());
        }
        assertEquals(0, twilio.getAllServeEvents().size(), "no Twilio call for a number with no agreement");
    }

    @Test
    void only_create_verification_and_create_check_are_ever_called() {
        String phone = agreed();
        stubSend(201, "{\"status\":\"pending\"}");
        stubCheck(200, "{\"status\":\"pending\"}");

        sender.sendCode(phone);
        sender.checkCode(phone, "111111");
        stubCheck(200, "{\"status\":\"approved\"}");
        sender.checkCode(phone, "222222");
        stubSend(429, "{\"code\":60203}");
        sender.sendCode(phone);

        assertEquals(4, twilio.getAllServeEvents().size());
        for (ServeEvent event : twilio.getAllServeEvents()) {
            assertEquals("POST", event.getRequest().getMethod().getName());
            assertTrue(Set.of(VERIFICATIONS, CHECK).contains(event.getRequest().getUrl()), event.getRequest().getUrl());
        }
    }

    @Test
    void no_secret_or_code_reaches_the_logs() {
        String phone = agreed();
        stubSend(500, "{\"code\":20500}");
        stubCheck(500, "{\"code\":20500}");
        try (LogCapture logs = LogCapture.open()) {
            sender.sendCode(phone);
            sender.checkCode(phone, "735102");
            String out = logs.all();
            assertFalse(out.contains(TestNumbers.API_KEY_SECRET), out);
            assertFalse(out.contains(TestNumbers.SID_API_KEY), out);
            assertFalse(out.contains("735102"), out);
            assertFalse(out.contains(phone), out);
        }
    }

    private static Set<String> formKeys(String body) {
        return java.util.Arrays.stream(body.split("&")).map(p -> p.split("=", 2)[0])
                .collect(java.util.stream.Collectors.toSet());
    }
}
