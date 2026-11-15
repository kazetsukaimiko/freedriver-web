package io.freedriver.app.appliances;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AppliancesConfigTest {

    @Test
    void defaults_keep_configured_timeout() {
        AppliancesConfig config = config(Duration.ofSeconds(5), Duration.ofSeconds(30));
        assertEquals(Duration.ofSeconds(5), config.boundedCommandTimeout());
        assertEquals(Duration.ofSeconds(30), config.boundedCommandCeiling());
        assertEquals(Duration.ofMillis(50), config.boundedCommandFloor());
    }

    @Test
    void zero_negative_max_use_30s_ceiling() {
        assertEquals(Duration.ofSeconds(30), config(Duration.ofSeconds(60), Duration.ZERO).boundedCommandTimeout());
        assertEquals(Duration.ofSeconds(30), config(Duration.ofSeconds(60), Duration.ofSeconds(-1)).boundedCommandTimeout());
    }

    @Test
    void zero_negative_timeout_default_5s_then_clamp() {
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

    @Test
    void defaults_match_the_former_property_defaults() {
        AppliancesConfig config = TestAppliancesConfig.of(Map.of());
        assertFalse(config.enabled());
        assertFalse(config.liveCommands());
        assertFalse(config.mock());
        assertFalse(config.mockRefresh());
        assertEquals(Duration.ofSeconds(20), config.staleAfter());
        assertEquals(Duration.ofSeconds(5), config.commandTimeout());
        assertEquals(Duration.ofSeconds(30), config.commandTimeoutMax());
        assertEquals(30, config.rateLimit().permits());
        assertEquals(Duration.ofSeconds(60), config.rateLimit().window());
    }

    private static AppliancesConfig config(Duration timeout, Duration max) {
        return TestAppliancesConfig.of(Map.of(
                "command-timeout", timeout.toString(),
                "command-timeout-max", max.toString()));
    }
}
