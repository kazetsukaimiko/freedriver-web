package io.freedriver.app.security;

import io.freedriver.app.appliances.AppliancesConfig;
import io.freedriver.app.appliances.TestAppliancesConfig;
import io.freedriver.ratelimit.MemoryHitWindow;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallerRateLimiterTest {

    private static final long WINDOW_MS = 60_000;

    private static final AppliancesConfig CONFIG = TestAppliancesConfig.of(Map.of(
            "rate-limit.permits", "2",
            "rate-limit.window", "60s"));

    @Test
    void tryAcquire_rejects_after_permit_window() {
        CallerRateLimiter limiter = new CallerRateLimiter(CONFIG, new MemoryHitWindow());

        assertTrue(limiter.tryAcquire("invites", "ada"));
        assertTrue(limiter.tryAcquire("invites", "ada"));
        assertFalse(limiter.tryAcquire("invites", "ada"));

        MemoryHitWindow shared = new MemoryHitWindow();
        CallerRateLimiter first = new CallerRateLimiter(CONFIG, shared);
        CallerRateLimiter second = new CallerRateLimiter(CONFIG, shared);
        assertTrue(first.tryAcquire("invites", "ada"));
        assertTrue(second.tryAcquire("invites", "ada"));
        assertFalse(first.tryAcquire("invites", "ada"));
    }

    @Test
    void the_stored_key_is_bucket_colon_caller() {
        MemoryHitWindow hits = new MemoryHitWindow();
        CallerRateLimiter limiter = new CallerRateLimiter(CONFIG, hits);

        assertTrue(limiter.tryAcquire("appliances.commands", "ada"));

        assertEquals("appliances.commands:ada", CallerRateLimiter.key("appliances.commands", "ada"));
        assertEquals(1, hits.count("appliances.commands:ada", WINDOW_MS));
        assertEquals(0, hits.count("command:ada", WINDOW_MS));
        assertEquals(0, hits.count("ada", WINDOW_MS));
    }

    @Test
    void two_callers_fill_one_bucket_independently() {
        MemoryHitWindow hits = new MemoryHitWindow();
        CallerRateLimiter limiter = new CallerRateLimiter(CONFIG, hits);

        assertTrue(limiter.tryAcquire("instances", "ada"));
        assertTrue(limiter.tryAcquire("instances", "ada"));
        assertFalse(limiter.tryAcquire("instances", "ada"));

        assertTrue(limiter.tryAcquire("instances", "yuni"));
        assertTrue(limiter.tryAcquire("instances", "yuni"));
        assertFalse(limiter.tryAcquire("instances", "yuni"));

        assertEquals(2, hits.count("instances:ada", WINDOW_MS));
        assertEquals(2, hits.count("instances:yuni", WINDOW_MS));
    }

    @Test
    void one_callers_two_buckets_fill_independently() {
        MemoryHitWindow hits = new MemoryHitWindow();
        CallerRateLimiter limiter = new CallerRateLimiter(CONFIG, hits);

        assertTrue(limiter.tryAcquire("invites", "ada"));
        assertTrue(limiter.tryAcquire("invites", "ada"));
        assertFalse(limiter.tryAcquire("invites", "ada"));

        assertTrue(limiter.tryAcquire("phones", "ada"));
        assertTrue(limiter.tryAcquire("phones", "ada"));
        assertFalse(limiter.tryAcquire("phones", "ada"));

        assertEquals(2, hits.count("invites:ada", WINDOW_MS));
        assertEquals(2, hits.count("phones:ada", WINDOW_MS));
    }
}
