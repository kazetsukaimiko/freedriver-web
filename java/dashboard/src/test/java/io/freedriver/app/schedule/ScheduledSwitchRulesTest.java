package io.freedriver.app.schedule;

import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScheduledSwitchRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");
    private static final UUID HOUSE = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void a_day_is_the_longest_wait_and_the_subject_is_required() {
        assertDoesNotThrow(() -> ScheduledSwitchRules.check(
                HOUSE, "fridge", NOW.plusSeconds(86_400), "mcp-switch", NOW));
        assertThrows(BadRequestException.class, () -> ScheduledSwitchRules.check(
                HOUSE, "fridge", NOW.plusSeconds(86_401), "mcp-switch", NOW));
        assertThrows(BadRequestException.class, () -> ScheduledSwitchRules.check(
                HOUSE, "fridge", NOW.plusSeconds(30), " ", NOW));
        assertThrows(BadRequestException.class, () -> ScheduledSwitchRules.check(
                HOUSE, "fridge", NOW.minusSeconds(6), "mcp-switch", NOW));
    }
}
