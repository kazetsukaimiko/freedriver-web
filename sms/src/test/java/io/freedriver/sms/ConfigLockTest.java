package io.freedriver.sms;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks on application.properties that keep production safe. */
class ConfigLockTest {

    private static Properties properties() throws IOException {
        Properties p = new Properties();
        try (InputStream in = ConfigLockTest.class.getResourceAsStream("/application.properties")) {
            assertNotNull(in);
            p.load(in);
        }
        return p;
    }

    @Test
    void http_client_logging_is_off_in_every_profile() throws IOException {
        Properties p = properties();
        for (String category : new String[]{"jdk.internal.httpclient", "jdk.httpclient", "com.twilio", "org.apache.http"}) {
            assertEquals("OFF", p.getProperty("quarkus.log.category.\"" + category + "\".level"), category);
        }
        for (String key : p.stringPropertyNames()) {
            if (key.contains("log.category") && (key.contains("httpclient") || key.contains("twilio") || key.contains("apache.http"))) {
                assertEquals("OFF", p.getProperty(key), key);
            }
            assertFalse(key.contains("jdk.httpclient.HttpClient.log"), key);
            assertFalse(key.startsWith("quarkus.rest-client") && key.endsWith("logging.scope"), key);
        }
    }

    @Test
    void base_url_is_pinned_outside_the_test_profile() throws IOException {
        Properties p = properties();
        assertEquals("https://verify.twilio.com", p.getProperty("freedriver.sms.twilio.base-url"));
        for (String key : p.stringPropertyNames()) {
            if (key.endsWith("freedriver.sms.twilio.base-url")) {
                assertTrue(key.equals("freedriver.sms.twilio.base-url") || key.startsWith("%test."), key);
            }
        }
    }

    @Test
    void production_has_no_daily_cap_default_and_uses_twilio() throws IOException {
        Properties p = properties();
        assertFalse(p.containsKey("freedriver.sms.daily-send-cap"));
        assertFalse(p.containsKey("%prod.freedriver.sms.daily-send-cap"));
        assertEquals("twilio", p.getProperty("freedriver.sms.sender"));
        assertFalse(p.containsKey("%prod.freedriver.sms.sender"));
    }
}
