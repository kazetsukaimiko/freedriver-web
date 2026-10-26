package io.freedriver.sms.twilio;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.freedriver.sms.invite.InvitationLink;
import io.freedriver.sms.otp.SendOutcome;
import io.freedriver.sms.support.LogCapture;
import io.freedriver.sms.support.TestNumbers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Messages client called directly against a fake Twilio endpoint. */
class TwilioMessagesSenderTest {

    private static final String FROM = "+15555550199";
    private static final String PATH = "/2010-04-01/Accounts/" + TestNumbers.SID_ACCOUNT + "/Messages.json";

    private WireMockServer twilio;

    @BeforeEach
    void start() {
        twilio = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        twilio.start();
    }

    @AfterEach
    void stop() {
        twilio.stop();
    }

    private TwilioMessagesSender sender(String from) {
        TwilioCredentials credentials = new TwilioCredentials(TestNumbers.SID_ACCOUNT, TestNumbers.SID_API_KEY,
                TestNumbers.API_KEY_SECRET, TestNumbers.SID_SERVICE);
        return new TwilioMessagesSender(credentials, from, Duration.ofSeconds(2),
                URI.create("http://localhost:" + twilio.port()));
    }

    @Test
    void production_posts_to_the_messages_api() {
        TwilioCredentials credentials = new TwilioCredentials(TestNumbers.SID_ACCOUNT, TestNumbers.SID_API_KEY,
                TestNumbers.API_KEY_SECRET, TestNumbers.SID_SERVICE);
        TwilioMessagesSender live = new TwilioMessagesSender(credentials, FROM, Duration.ofSeconds(5));
        assertEquals(URI.create("https://api.twilio.com" + PATH), live.endpoint());
    }

    @Test
    void one_create_is_one_messages_post_and_not_a_verification() {
        String phone = "+15555550123";
        String text = InvitationLink.text("https://app.freedriver.io", phone);
        twilio.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody("{\"sid\":\"SM1\",\"status\":\"queued\"}")));

        assertEquals(SendOutcome.SENT, sender(FROM).send(phone, text));

        String basic = "Basic " + Base64.getEncoder().encodeToString(
                (TestNumbers.SID_API_KEY + ":" + TestNumbers.API_KEY_SECRET).getBytes(StandardCharsets.UTF_8));
        twilio.verify(1, postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo(basic))
                .withFormParam("To", equalTo(phone))
                .withFormParam("From", equalTo(FROM))
                .withFormParam("Body", equalTo(text)));
        assertEquals(1, twilio.getAllServeEvents().size());
        String raw = twilio.getAllServeEvents().getFirst().getRequest().getBodyAsString();
        assertFalse(raw.contains("Channel"));
        assertFalse(raw.contains("?"));
        assertTrue(raw.contains("login"));
    }

    @Test
    void a_refused_send_is_one_call_and_the_log_keeps_the_number_masked() {
        String phone = "+15555550123";
        twilio.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":21211,\"message\":\"bad " + phone + "\"}")));

        try (LogCapture logs = LogCapture.open()) {
            assertEquals(SendOutcome.FAILED, sender(FROM).send(phone, "Sign in to Freedriver: https://app.freedriver.io/login#" + phone));
            assertTrue(logs.all().contains("21211"), logs.all());
            assertFalse(logs.all().contains(phone), logs.all());
            assertFalse(logs.all().contains("bad " + phone.substring(0, 6)), logs.all());
        }
        assertEquals(1, twilio.getAllServeEvents().size());
    }

    @Test
    void a_missing_from_number_makes_no_twilio_call() {
        assertEquals(SendOutcome.FAILED, sender(null).send("+15555550123", "link"));
        assertEquals(0, twilio.getAllServeEvents().size());
        assertEquals(null, TwilioMessagesSender.fromNumber(null));
        assertEquals(null, TwilioMessagesSender.fromNumber("  "));
        assertEquals(FROM, TwilioMessagesSender.fromNumber(" " + FROM + " "));
        IllegalStateException malformed = assertThrows(IllegalStateException.class,
                () -> TwilioMessagesSender.fromNumber("not-a-number"));
        assertFalse(malformed.getMessage().contains("not-a-number"));
    }
}
