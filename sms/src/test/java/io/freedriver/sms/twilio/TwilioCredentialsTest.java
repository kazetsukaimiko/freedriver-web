package io.freedriver.sms.twilio;

import io.freedriver.sms.phones.ConsentLedger;
import io.freedriver.sms.support.Json;
import io.freedriver.sms.support.LogCapture;
import io.freedriver.sms.support.TestNumbers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwilioCredentialsTest {

    @TempDir
    Path dataDir;

    private static Map<String, String> complete() {
        Map<String, String> env = new HashMap<>();
        env.put(TwilioCredentials.ACCOUNT_SID, TestNumbers.SID_ACCOUNT);
        env.put(TwilioCredentials.API_KEY_SID, TestNumbers.SID_API_KEY);
        env.put(TwilioCredentials.API_KEY_SECRET, TestNumbers.API_KEY_SECRET);
        env.put(TwilioCredentials.VERIFY_SERVICE_SID, TestNumbers.SID_SERVICE);
        return env;
    }

    @ParameterizedTest
    @ValueSource(strings = {"TWILIO_ACCOUNT_SID", "TWILIO_API_KEY_SID", "TWILIO_API_KEY_SECRET", "TWILIO_VERIFY_SERVICE_SID"})
    void each_missing_setting_stops_startup_by_name(String name) {
        Map<String, String> env = complete();
        env.remove(name);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> TwilioCredentials.load(env::get));
        assertTrue(e.getMessage().contains(name), e.getMessage());
        assertFalse(e.getMessage().contains(TestNumbers.API_KEY_SECRET));

        env.put(name, "   ");
        assertThrows(IllegalStateException.class, () -> TwilioCredentials.load(env::get));
    }

    @Test
    void malformed_sids_stop_startup_without_printing_values() {
        Map<String, String> env = complete();
        env.put(TwilioCredentials.API_KEY_SID, "AC-not-an-api-key");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> TwilioCredentials.load(env::get));
        assertTrue(e.getMessage().contains("TWILIO_API_KEY_SID"));
        assertFalse(e.getMessage().contains("AC-not-an-api-key"));
    }

    @Test
    void to_string_prints_no_values() {
        TwilioCredentials credentials = TwilioCredentials.load(complete()::get);
        assertEquals("TwilioCredentials[redacted]", credentials.toString());
    }

    @Test
    void startup_logs_that_credentials_loaded_and_no_values() {
        try (LogCapture logs = LogCapture.open()) {
            TwilioSenderProducer.create(complete()::get, "https://verify.twilio.com", Duration.ofSeconds(5), false,
                    new ConsentLedger(dataDir, Json.mapper()));
            String out = logs.all();
            assertTrue(out.contains("Twilio credentials loaded"), out);
            for (String value : complete().values()) {
                assertFalse(out.contains(value), out);
            }
        }
    }

    @Test
    void production_startup_refuses_a_non_twilio_base_url() {
        ConsentLedger ledger = new ConsentLedger(dataDir, Json.mapper());
        assertThrows(IllegalStateException.class, () -> TwilioSenderProducer.create(complete()::get,
                "http://localhost:8089", Duration.ofSeconds(5), false, ledger));
        assertThrows(IllegalStateException.class, () -> TwilioSenderProducer.create(complete()::get,
                "http://verify.twilio.com", Duration.ofSeconds(5), false, ledger));
    }
}
