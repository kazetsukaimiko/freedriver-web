package io.freedriver.app.appliances;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRateLimiterTest {

    @Test
    void tryAcquire_rejects_after_permit_window() {
        AppliancesConfig config = new AppliancesConfig();
        config.rateLimitPermits = 2;
        config.rateLimitWindow = Duration.ofSeconds(60);
        CommandRateLimiter limiter = new CommandRateLimiter(config);

        assertTrue(limiter.tryAcquire("scott"));
        assertTrue(limiter.tryAcquire("scott"));
        assertFalse(limiter.tryAcquire("scott"));
        assertTrue(limiter.tryAcquire("yuni"));
    }
}
