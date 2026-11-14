package io.freedriver.sms.security;

import io.freedriver.ratelimit.MemoryHitWindow;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhoneRateLimiterTest {

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
    void many_distinct_numbers_leave_both_counters_once_their_window_has_passed() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-27T12:00:00Z"));
        PhoneRateLimiter limiter = new PhoneRateLimiter(Duration.ofMinutes(15), 2, 2, new MemoryHitWindow(clock));

        int early = 200;
        for (int i = 0; i < early; i++) {
            assertTrue(limiter.tryAcquireSend(phone(i)));
            limiter.recordWrongCode(phone(i));
        }

        clock.now = Instant.parse("2026-09-27T12:10:00Z");
        int later = 40;
        for (int i = early; i < early + later; i++) {
            assertTrue(limiter.tryAcquireSend(phone(i)));
            limiter.recordWrongCode(phone(i));
            limiter.recordWrongCode(phone(i));
        }

        // Still inside the early window: another request does not drop those numbers.
        clock.now = Instant.parse("2026-09-27T12:14:59Z");
        assertTrue(limiter.tryAcquireSend(phone(early)));
        assertTrue(limiter.codeChecksLocked(phone(early)));

        // 12:00 numbers have passed the window. The 12:10 cohort, and one new send, remain.
        clock.now = Instant.parse("2026-09-27T12:15:00Z");
        assertTrue(limiter.tryAcquireSend(phone(9_000)));
        assertFalse(limiter.codeChecksLocked(phone(9_001)));
        assertFalse(limiter.tryAcquireSend(phone(early)), "a number still inside the window keeps its send count");
        assertTrue(limiter.codeChecksLocked(phone(early)), "a number still inside the window keeps its wrong-code count");

        // Every recorded hit is now outside the window.
        clock.now = Instant.parse("2026-09-27T12:30:00Z");
        assertFalse(limiter.codeChecksLocked(phone(9_001)));
        assertTrue(limiter.tryAcquireSend(phone(0)));
        assertFalse(limiter.codeChecksLocked(phone(early)));
    }

    @Test
    void parallel_code_checks_reserve_only_the_limit() throws Exception {
        PhoneRateLimiter limiter = new PhoneRateLimiter(Duration.ofMinutes(15), 5, 5, new MemoryHitWindow(Clock.systemUTC()));
        String phone = "+15555550100";
        int guesses = 32;
        ExecutorService pool = Executors.newFixedThreadPool(guesses);
        CyclicBarrier barrier = new CyclicBarrier(guesses);
        try {
            List<Future<PhoneRateLimiter.CodeCheck>> futures = new ArrayList<>();
            for (int i = 0; i < guesses; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return limiter.tryAcquireCodeCheck(phone);
                }));
            }
            List<PhoneRateLimiter.CodeCheck> held = new ArrayList<>();
            for (Future<PhoneRateLimiter.CodeCheck> future : futures) {
                PhoneRateLimiter.CodeCheck check = future.get(30, TimeUnit.SECONDS);
                if (check != null) {
                    held.add(check);
                }
            }
            assertEquals(5, held.size());
            assertTrue(limiter.codeChecksLocked(phone));

            held.getFirst().release();
            assertFalse(limiter.codeChecksLocked(phone));
            PhoneRateLimiter.CodeCheck again = limiter.tryAcquireCodeCheck(phone);
            assertNotNull(again);
            assertTrue(limiter.codeChecksLocked(phone));

            again.release();
            for (PhoneRateLimiter.CodeCheck check : held) {
                check.release();
            }
            assertFalse(limiter.codeChecksLocked(phone));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void a_reserved_code_check_expires_with_the_window_and_a_success_clears_it() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-27T12:00:00Z"));
        PhoneRateLimiter limiter = new PhoneRateLimiter(Duration.ofMinutes(15), 5, 2, new MemoryHitWindow(clock));
        String phone = "+15555550100";

        PhoneRateLimiter.CodeCheck first = limiter.tryAcquireCodeCheck(phone);
        PhoneRateLimiter.CodeCheck second = limiter.tryAcquireCodeCheck(phone);
        assertNotNull(first);
        assertNotNull(second);
        first.keep();
        second.keep();
        assertTrue(limiter.codeChecksLocked(phone));
        assertNull(limiter.tryAcquireCodeCheck(phone));

        // Same boundary as the send counters: the window has passed, so the number is gone.
        clock.now = Instant.parse("2026-09-27T12:15:00Z");
        assertFalse(limiter.codeChecksLocked(phone));
        PhoneRateLimiter.CodeCheck later = limiter.tryAcquireCodeCheck(phone);
        assertNotNull(later);

        limiter.clearWrongCodes(phone);
        later.keep();
        assertFalse(limiter.codeChecksLocked(phone));
        assertNotNull(limiter.tryAcquireCodeCheck(phone));
    }

    private static String phone(int n) {
        return "+1555" + String.format("%07d", n);
    }
}
