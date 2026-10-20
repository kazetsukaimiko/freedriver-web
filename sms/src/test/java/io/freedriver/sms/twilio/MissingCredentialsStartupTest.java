package io.freedriver.sms.twilio;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** With the Twilio sender selected and a credential missing, the service does not start. */
@QuarkusMainTest
@TestProfile(MissingCredentialsStartupTest.MissingSecret.class)
class MissingCredentialsStartupTest {

    public static class MissingSecret implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "freedriver.sms.sender", "twilio",
                    "TWILIO_ACCOUNT_SID", "AC" + "0".repeat(32),
                    "TWILIO_API_KEY_SID", "SK" + "1".repeat(32),
                    "TWILIO_VERIFY_SERVICE_SID", "VA" + "2".repeat(32));
        }
    }

    @Test
    @Launch(value = {}, exitCode = 1)
    void missing_api_key_secret_fails_startup(LaunchResult result) {
        String output = result.getOutput() + "\n" + result.getErrorOutput();
        assertTrue(output.contains("TWILIO_API_KEY_SECRET"), output);
        assertTrue(!output.contains("Twilio credentials loaded"), output);
    }
}
