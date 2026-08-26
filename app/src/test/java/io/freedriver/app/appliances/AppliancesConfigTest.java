package io.freedriver.app.appliances;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppliancesConfigTest {

    @Test
    void defaults_keep_configured_timeout() {
        AppliancesConfig config = config(Duration.ofSeconds(5), Duration.ofSeconds(30));
        assertEquals(Duration.ofSeconds(5), config.boundedCommandTimeout());
        assertEquals(Duration.ofSeconds(30), config.boundedCommandCeiling());
        assertEquals(Duration.ofMillis(50), config.boundedCommandFloor());
    }

    @Test
    void null_zero_negative_max_use_30s_ceiling() {
        assertEquals(Duration.ofSeconds(30), config(Duration.ofSeconds(60), null).boundedCommandTimeout());
        assertEquals(Duration.ofSeconds(30), config(Duration.ofSeconds(60), Duration.ZERO).boundedCommandTimeout());
        assertEquals(Duration.ofSeconds(30), config(Duration.ofSeconds(60), Duration.ofSeconds(-1)).boundedCommandTimeout());
    }

    @Test
    void null_zero_negative_timeout_default_5s_then_clamp() {
        assertEquals(Duration.ofSeconds(5), config(null, Duration.ofSeconds(30)).boundedCommandTimeout());
        assertEquals(Duration.ofSeconds(5), config(Duration.ZERO, Duration.ofSeconds(30)).boundedCommandTimeout());
        assertEquals(Duration.ofSeconds(5), config(Duration.ofSeconds(-3), Duration.ofSeconds(30)).boundedCommandTimeout());
    }

    @Test
    void timeout_above_ceiling_is_capped() {
        assertEquals(Duration.ofSeconds(10), config(Duration.ofSeconds(45), Duration.ofSeconds(10)).boundedCommandTimeout());
    }

    @Test
    void timeout_below_floor_is_raised() {
        assertEquals(Duration.ofMillis(50), config(Duration.ofMillis(10), Duration.ofSeconds(30)).boundedCommandTimeout());
    }

    private static AppliancesConfig config(Duration timeout, Duration max) {
        AppliancesConfig config = new AppliancesConfig();
        config.commandTimeout = timeout;
        config.commandTimeoutMax = max;
        return config;
    }
}
