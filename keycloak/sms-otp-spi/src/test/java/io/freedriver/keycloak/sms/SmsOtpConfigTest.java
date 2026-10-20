package io.freedriver.keycloak.sms;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmsOtpConfigTest {

    @Test
    void missingBlankAndPlaceholderAreFailClosed() {
        assertTrue(SmsOtpConfig.normalize(null).isEmpty());
        assertTrue(SmsOtpConfig.normalize("").isEmpty());
        assertTrue(SmsOtpConfig.normalize("   ").isEmpty());
        assertTrue(SmsOtpConfig.normalize(SmsOtpConfig.PLACEHOLDER).isEmpty());
        assertTrue(SmsOtpConfig.normalize("  " + SmsOtpConfig.PLACEHOLDER + "\n").isEmpty());
        assertEquals("real-secret", SmsOtpConfig.normalize("  real-secret  ").orElseThrow());
    }
}
