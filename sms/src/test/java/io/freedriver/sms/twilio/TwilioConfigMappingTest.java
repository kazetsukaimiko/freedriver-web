package io.freedriver.sms.twilio;

import io.freedriver.sms.SmsConfig;
import io.freedriver.sms.support.TestNumbers;
import io.quarkus.runtime.configuration.DurationConverter;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The four TWILIO_ variables reach {@link SmsConfig.Twilio} through application.properties. */
class TwilioConfigMappingTest {

    @Test
    void the_environment_names_fill_the_mapping_and_load_the_credentials() {
        Map<String, String> env = new HashMap<>();
        env.put(TwilioCredentials.ACCOUNT_SID, TestNumbers.SID_ACCOUNT);
        env.put(TwilioCredentials.API_KEY_SID, TestNumbers.SID_API_KEY);
        env.put(TwilioCredentials.API_KEY_SECRET, TestNumbers.API_KEY_SECRET);
        env.put(TwilioCredentials.VERIFY_SERVICE_SID, TestNumbers.SID_SERVICE);

        SmsConfig.Twilio twilio = mapped(env).twilio();
        TwilioCredentials credentials = TwilioCredentials.load(TwilioCredentials.lookup(twilio));

        assertEquals(new TwilioCredentials(TestNumbers.SID_ACCOUNT, TestNumbers.SID_API_KEY,
                TestNumbers.API_KEY_SECRET, TestNumbers.SID_SERVICE), credentials);
        assertEquals("TwilioCredentials[redacted]", credentials.toString());
        assertFalse(String.valueOf(twilio).contains(TestNumbers.API_KEY_SECRET));
    }

    @Test
    void unset_variables_start_the_mapping_and_fail_the_sender_by_name() {
        SmsConfig.Twilio twilio = mapped(Map.of(TwilioCredentials.ACCOUNT_SID, TestNumbers.SID_ACCOUNT)).twilio();

        assertTrue(twilio.apiKeySecret().isEmpty());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> TwilioCredentials.load(TwilioCredentials.lookup(twilio)));
        assertEquals("Twilio Verify settings are not usable. Missing: TWILIO_API_KEY_SID, "
                + "TWILIO_API_KEY_SECRET, TWILIO_VERIFY_SERVICE_SID.", e.getMessage());
    }

    /** application.properties without profiles, plus the variables and the two settings with no default. */
    private static SmsConfig mapped(Map<String, String> env) {
        Map<String, String> application = new HashMap<>();
        Properties properties = new Properties();
        try (InputStream in = TwilioConfigMappingTest.class.getResourceAsStream("/application.properties")) {
            properties.load(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        properties.stringPropertyNames().stream()
                .filter(name -> !name.startsWith("%"))
                .forEach(name -> application.put(name, properties.getProperty(name)));
        application.put("freedriver.sms.daily-send-cap", "1");
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .addDefaultInterceptors()
                .withConverter(Duration.class, 200, new DurationConverter())
                .withSources(new PropertiesConfigSource(application, "application.properties", 250))
                .withSources(new PropertiesConfigSource(env, "env", 300))
                .withMapping(SmsConfig.class)
                .build();
        return config.getConfigMapping(SmsConfig.class);
    }
}
