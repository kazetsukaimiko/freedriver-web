package io.freedriver.autonomymcp;

import io.freedriver.mcp.DeclaredToolArguments;
import io.freedriver.ratelimit.MemoryHitWindow;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pieces of switch_appliances that run without the server: the limit, the duplicate rule, the sentences. */
class SwitchAppliancesUnitTest {

    private static final String KEY = "tool:ada:switch_appliances";
    private static final long WINDOW_MS = 60_000;

    @Test
    void the_limit_takes_every_hit_or_none() {
        MemoryHitWindow hits = new MemoryHitWindow();
        ToolRateLimiter limiter = new ToolRateLimiter(5, WINDOW_MS, hits);

        assertTrue(limiter.tryAcquire("ada", "switch_appliances", 3));
        assertEquals(3, hits.count(KEY, WINDOW_MS));

        assertFalse(limiter.tryAcquire("ada", "switch_appliances", 3));
        assertEquals(3, hits.count(KEY, WINDOW_MS), "a refused call gives back the hits it took");

        assertTrue(limiter.tryAcquire("ada", "switch_appliances", 2));
        assertEquals(5, hits.count(KEY, WINDOW_MS));
        assertFalse(limiter.tryAcquire("ada", "switch_appliances", 1));
        assertTrue(limiter.tryAcquire("bea", "switch_appliances", 5), "each caller has its own window");
    }

    @Test
    void one_hit_is_the_single_call_limit() {
        ToolRateLimiter limiter = new ToolRateLimiter(1, WINDOW_MS, new MemoryHitWindow());
        assertTrue(limiter.tryAcquire("ada", "list_appliances"));
        assertFalse(limiter.tryAcquire("ada", "list_appliances"));
    }

    @Test
    void the_same_name_with_the_same_delay_is_listed_twice() {
        assertEquals(Optional.of("fridge"), AutonomyTools.listedTwice(List.of(
                new ApplianceSwitch("fridge", true, null),
                new ApplianceSwitch("porch", true, null),
                new ApplianceSwitch("fridge", false, 0))));
        assertEquals(Optional.of("fridge"), AutonomyTools.listedTwice(List.of(
                new ApplianceSwitch("fridge", true, 300),
                new ApplianceSwitch("fridge", false, 300))));
    }

    @Test
    void the_same_name_with_different_delays_is_allowed() {
        assertEquals(Optional.empty(), AutonomyTools.listedTwice(List.of(
                new ApplianceSwitch("fridge", true, null),
                new ApplianceSwitch("fridge", false, 300),
                new ApplianceSwitch("Fridge", true, null))));
    }

    @Test
    void a_refused_entry_names_its_appliance() {
        assertEquals("Porch light: too many requests. Try again in a minute.", Spoken.refused("porch-light", 429));
        assertEquals("Fridge: the house could not be reached.", Spoken.refused("fridge", 503));
        assertEquals("Fridge: that house is not in the list.", Spoken.refused("fridge", 404));
    }

    @Test
    void an_entry_carries_only_its_own_fields() throws Exception {
        Method tool = AutonomyTools.class.getDeclaredMethod("switchAppliances", UUID.class, List.class);
        Type entries = tool.getGenericParameterTypes()[1];
        JsonObject entry = new JsonObject().put("applianceName", "fridge").put("on", true).put("delaySeconds", 5);
        assertFalse(DeclaredToolArguments.undeclaredInside(entries, new JsonArray().add(entry)));
        assertTrue(DeclaredToolArguments.undeclaredInside(entries,
                new JsonArray().add(entry).add(entry.copy().put("duration", 300))));
        assertFalse(DeclaredToolArguments.undeclaredInside(String.class, "fridge"));
    }
}
