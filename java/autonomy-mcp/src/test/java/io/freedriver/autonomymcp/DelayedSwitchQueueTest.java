package io.freedriver.autonomymcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DelayedSwitchQueueTest {

    @Test
    void a_stored_switch_is_claimed_once_and_survives_until_another_process_fires_it() {
        MemorySwitchKeys keys = new MemorySwitchKeys();
        DelayedSwitchQueue first = new SwitchDelayRecords(keys);
        DelayedSwitchQueue second = new SwitchDelayRecords(keys);
        long due = 5_000L;
        first.schedule("switch-1", due, "{\"on\":false}", SwitchDelay.payloadTtlSeconds(300));

        assertEquals(3900L, keys.ttl("switch-1"));
        assertNull(first.lockDue(due - 1));
        assertEquals("switch-1", first.lockDue(due));
        assertEquals("{\"on\":false}", second.payload("switch-1"));
        assertNull(second.lockDue(due));
        assertEquals("switch-1", second.lockDue(due + SwitchDelay.LOCK_SECONDS * 1000L));
        second.remove("switch-1");
        assertNull(first.payload("switch-1"));
        assertNull(second.lockDue(due + SwitchDelay.LOCK_SECONDS * 1000L));
    }

    @Test
    void spoken_delay_uses_minutes_when_the_wait_is_a_whole_number_of_them() {
        assertEquals("Fridge will turn off in 5 minutes.", Spoken.scheduled("fridge", false, 300));
        assertEquals("Fridge will turn on in 1 minute.", Spoken.scheduled("fridge", true, 60));
        assertEquals("Fridge will turn off in 1 second.", Spoken.scheduled("fridge", false, 1));
        assertEquals("Fridge will turn off in 90 seconds.", Spoken.scheduled("fridge", false, 90));
    }
}
