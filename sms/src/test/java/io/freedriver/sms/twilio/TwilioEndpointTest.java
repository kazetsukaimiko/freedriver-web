package io.freedriver.sms.twilio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TwilioEndpointTest {

    @ParameterizedTest
    @ValueSource(strings = {"https://verify.twilio.com", "https://verify.twilio.com/", "HTTPS://Verify.Twilio.com", "https://verify.twilio.com:443"})
    void production_accepts_only_the_twilio_verify_origin(String configured) {
        assertEquals(URI.create("https://verify.twilio.com"), TwilioEndpoint.resolve(configured, false).base());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://verify.twilio.com",
            "http://localhost:8089",
            "https://localhost:8089",
            "https://verify.twilio.com.evil.example",
            "https://evil.example/verify.twilio.com",
            "https://user@verify.twilio.com",
            "https://verify.twilio.com:8443",
            "https://verify.twilio.com/v2",
            "https://verify.twilio.com?x=1",
            "https://api.twilio.com",
            "",
            "not a url"})
    void production_refuses_any_other_host_or_plain_http(String configured) {
        assertThrows(IllegalStateException.class, () -> TwilioEndpoint.resolve(configured, false));
    }

    @Test
    void test_profile_may_point_at_a_fake_endpoint() {
        assertEquals(URI.create("http://localhost:8089"), TwilioEndpoint.resolve("http://localhost:8089", true).base());
        assertThrows(IllegalStateException.class, () -> TwilioEndpoint.resolve("ftp://localhost", true));
        assertThrows(IllegalStateException.class, () -> TwilioEndpoint.resolve("http://localhost:8089/v2", true));
    }

    @Test
    void operation_urls_are_the_two_verify_calls() {
        TwilioEndpoint endpoint = TwilioEndpoint.resolve("https://verify.twilio.com", false);
        String sid = "VA" + "2".repeat(32);
        assertEquals("https://verify.twilio.com/v2/Services/" + sid + "/Verifications", endpoint.verifications(sid).toString());
        assertEquals("https://verify.twilio.com/v2/Services/" + sid + "/VerificationCheck", endpoint.verificationCheck(sid).toString());
    }
}
