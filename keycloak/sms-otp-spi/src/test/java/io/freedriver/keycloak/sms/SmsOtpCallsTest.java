package io.freedriver.keycloak.sms;

import io.freedriver.sms.client.SmsClient;
import io.freedriver.sms.client.SmsClientConfig;
import io.freedriver.sms.client.SmsClientException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmsOtpCallsTest {

    @Test
    void productionClientIsSmsClientAtTheFixedBaseAndHidesTheSecret() {
        String secret = "keycloak-load-test-secret";
        SmsClient client = (SmsClient) SmsOtpCalls.client(secret);
        assertTrue(java.lang.reflect.Proxy.isProxyClass(client.getClass()));
        assertFalse(client.toString().contains(secret));
        assertEquals(SmsOtpConfig.BASE_URL, SmsClientConfig.BASE_URL);
        assertEquals(SmsOtpConfig.HEADER, SmsClientConfig.HEADER);
        assertEquals(SmsOtpConfig.PLACEHOLDER, SmsClientConfig.PLACEHOLDER);
    }

    @Test
    void exceptionMappingMatchesThePhonePages() {
        assertEquals(SmsOtpCalls.Outcome.INVALID_CODE,
                SmsOtpCalls.fromException(new SmsClientException(400, "invalid-code", null), true).outcome());
        assertEquals(SmsOtpCalls.Outcome.UNAVAILABLE,
                SmsOtpCalls.fromException(new SmsClientException(400, "invalid-code", null), false).outcome());
        assertEquals(SmsOtpCalls.Outcome.UNAVAILABLE,
                SmsOtpCalls.fromException(new SmsClientException(400, "bad-request", null), true).outcome());
        assertEquals(SmsOtpCalls.Outcome.CHECK_UNAVAILABLE,
                SmsOtpCalls.fromException(new SmsClientException(503, "unavailable", null), true).outcome());
        assertEquals(SmsOtpCalls.Outcome.UNAVAILABLE,
                SmsOtpCalls.fromException(new SmsClientException(503, "unavailable", null), false).outcome());
        assertEquals(SmsOtpCalls.Outcome.UNAVAILABLE,
                SmsOtpCalls.fromException(new SmsClientException(503, "stub", null), true).outcome());
        assertEquals(SmsOtpCalls.Outcome.UNAVAILABLE,
                SmsOtpCalls.fromException(new SmsClientException(401, "unauthorized", null), true).outcome());
        assertEquals(SmsOtpCalls.Outcome.UNAVAILABLE,
                SmsOtpCalls.fromException(new SmsClientException(403, "no", null), false).outcome());
        assertEquals(SmsOtpCalls.Outcome.RATE_LIMITED,
                SmsOtpCalls.fromException(new SmsClientException(429, "rate-limited", 900L), true).outcome());
        assertEquals(SmsOtpCalls.Outcome.EXPIRED,
                SmsOtpCalls.fromException(new SmsClientException(400, "invalid-code", null, "expired"), true).outcome());
        assertEquals(SmsOtpCalls.Outcome.INVALID_CODE,
                SmsOtpCalls.fromException(new SmsClientException(400, "invalid-code", null, "wrong_code"), true).outcome());
        assertEquals(SmsOtpCalls.Outcome.TOO_MANY_ATTEMPTS,
                SmsOtpCalls.fromException(new SmsClientException(429, "rate-limited", 900L, "too_many_attempts"), true)
                        .outcome());
        assertEquals(SmsOtpCalls.Outcome.RATE_LIMITED,
                SmsOtpCalls.fromException(new SmsClientException(429, "rate-limited", 900L, "too_many_attempts"), false)
                        .outcome());
    }

    @Test
    void retryAfterOutsideOneToThreeThousandSixHundredFallsBackToFifteenMinutes() {
        assertEquals(900, SmsOtpCalls.retrySeconds(900L));
        assertEquals(1, SmsOtpCalls.retrySeconds(1L));
        assertEquals(3600, SmsOtpCalls.retrySeconds(3600L));
        assertEquals(60, SmsOtpCalls.retrySeconds(60L));
        assertEquals(SmsOtpCalls.DEFAULT_RETRY_AFTER_SECONDS, SmsOtpCalls.retrySeconds(null));
        assertEquals(SmsOtpCalls.DEFAULT_RETRY_AFTER_SECONDS, SmsOtpCalls.retrySeconds(0L));
        assertEquals(SmsOtpCalls.DEFAULT_RETRY_AFTER_SECONDS, SmsOtpCalls.retrySeconds(3601L));
        assertEquals(SmsOtpCalls.DEFAULT_RETRY_AFTER_SECONDS, SmsOtpCalls.retrySeconds(86400L));
    }
}
