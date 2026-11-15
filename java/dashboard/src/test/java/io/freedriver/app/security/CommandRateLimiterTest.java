package io.freedriver.app.security;

import io.freedriver.app.appliances.AppliancesConfig;
import io.freedriver.app.appliances.TestAppliancesConfig;
import io.freedriver.ratelimit.MemoryHitWindow;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRateLimiterTest {

    @Test
    void tryAcquire_rejects_after_permit_window() {
        AppliancesConfig config = TestAppliancesConfig.of(Map.of(
                "rate-limit.permits", "2",
                "rate-limit.window", "60s"));
        CommandRateLimiter limiter = new CommandRateLimiter(config, new MemoryHitWindow());

        assertTrue(limiter.tryAcquire("ada"));
        assertTrue(limiter.tryAcquire("ada"));
        assertFalse(limiter.tryAcquire("ada"));
        assertTrue(limiter.tryAcquire("yuni"));

        MemoryHitWindow shared = new MemoryHitWindow();
        CommandRateLimiter first = new CommandRateLimiter(config, shared);
        CommandRateLimiter second = new CommandRateLimiter(config, shared);
        assertTrue(first.tryAcquire("ada"));
        assertTrue(second.tryAcquire("ada"));
        assertFalse(first.tryAcquire("ada"));
        assertTrue(second.tryAcquire("yuni"));
    }
}
