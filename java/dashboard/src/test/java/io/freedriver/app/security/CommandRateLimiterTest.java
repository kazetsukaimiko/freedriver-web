package io.freedriver.app.security;

import io.freedriver.app.appliances.AppliancesConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRateLimiterTest {

    @Test
    void tryAcquire_rejects_after_permit_window() {
        AppliancesConfig config = new AppliancesConfig();
        // Rate-limit fields stay package-private on AppliancesConfig. This test no longer shares that package.
        set(config, "rateLimitPermits", 2);
        set(config, "rateLimitWindow", Duration.ofSeconds(60));
        CommandRateLimiter limiter = new CommandRateLimiter(config);

        assertTrue(limiter.tryAcquire("ada"));
        assertTrue(limiter.tryAcquire("ada"));
        assertFalse(limiter.tryAcquire("ada"));
        assertTrue(limiter.tryAcquire("yuni"));
    }

    private static void set(AppliancesConfig config, String name, Object value) {
        try {
            Field field = AppliancesConfig.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(config, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
