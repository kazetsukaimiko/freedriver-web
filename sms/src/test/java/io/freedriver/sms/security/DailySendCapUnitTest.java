package io.freedriver.sms.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DailySendCapUnitTest {

    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void cap_holds_for_the_utc_day_and_resets_at_midnight() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-27T23:58:00Z"));
        DailySendCap cap = new DailySendCap(clock, 2);
        assertTrue(cap.tryAcquire());
        assertTrue(cap.tryAcquire());
        assertFalse(cap.tryAcquire());
        clock.now = Instant.parse("2026-09-27T23:59:59Z");
        assertFalse(cap.tryAcquire());
        clock.now = Instant.parse("2026-09-28T00:00:01Z");
        assertTrue(cap.tryAcquire());
    }

    @Test
    void per_number_windows_slide() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-27T12:00:00Z"));
        PhoneRateLimiter limiter = new PhoneRateLimiter(clock, java.time.Duration.ofMinutes(15), 2, 2);
        assertTrue(limiter.tryAcquireSend("+15555550100"));
        assertTrue(limiter.tryAcquireSend("+15555550100"));
        assertFalse(limiter.tryAcquireSend("+15555550100"));
        assertTrue(limiter.tryAcquireSend("+15555550101"));
        limiter.recordWrongCode("+15555550100");
        limiter.recordWrongCode("+15555550100");
        assertTrue(limiter.codeChecksLocked("+15555550100"));
        clock.now = Instant.parse("2026-09-27T12:15:00Z");
        assertTrue(limiter.tryAcquireSend("+15555550100"));
        assertFalse(limiter.codeChecksLocked("+15555550100"));
    }
}
