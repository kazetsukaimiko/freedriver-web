package io.freedriver.sms.api;

import io.freedriver.sms.otp.FakeSmsSender;
import io.freedriver.sms.phones.ConsentLedger;
import io.freedriver.sms.phones.ConsentPurpose;
import io.freedriver.sms.phones.ConsentRecord;
import io.freedriver.sms.phones.ConsentSource;
import io.freedriver.sms.phones.PhoneDirectory;
import io.freedriver.sms.phones.PhoneEntry;
import io.freedriver.sms.security.DailySendCap;
import io.freedriver.sms.security.PhoneRateLimiter;
import io.freedriver.sms.support.LogCapture;
import io.freedriver.sms.support.SmsCalls;
import io.freedriver.sms.support.TestNumbers;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(DailySendCapTest.SmallCap.class)
class DailySendCapTest {

    public static class SmallCap implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("freedriver.sms.daily-send-cap", "3");
        }
    }

    @Inject
    PhoneDirectory directory;
    @Inject
    ConsentLedger consents;
    @Inject
    FakeSmsSender sender;
    @Inject
    PhoneRateLimiter limiter;
    @Inject
    DailySendCap dailyCap;

    private String agreed() {
        String phone = TestNumbers.fresh();
        directory.add(new PhoneEntry(phone, "Guest", "guest.user", "dashboard", Instant.now()));
        consents.record(new ConsentRecord(phone, ConsentPurpose.SIGN_IN_CODES, TestNumbers.WORDING,
                ConsentSource.INVITE_PAGE, Instant.now()));
        return phone;
    }

    @Test
    void daily_cap_stops_sends_for_every_number_and_logs_an_error() {
        sender.reset();
        limiter.reset();
        dailyCap.reset();
        for (int i = 0; i < 3; i++) {
            SmsCalls.send(agreed()).statusCode(200).body("status", equalTo("sent"));
        }
        try (LogCapture logs = LogCapture.open()) {
            SmsCalls.send(agreed()).statusCode(429).body("error", equalTo("rate-limited"));
            SmsCalls.send(TestNumbers.fresh()).statusCode(429);
            assertTrue(logs.lines().stream().anyMatch(l -> (l.startsWith("ERROR") || l.startsWith("SEVERE")) && l.contains("daily send cap")),
                    logs.all());
        }
        assertEquals(3, sender.deliveries());
    }
}
