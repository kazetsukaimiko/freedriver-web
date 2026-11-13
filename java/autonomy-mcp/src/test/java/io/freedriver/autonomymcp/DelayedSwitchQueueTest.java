package io.freedriver.autonomymcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DelayedSwitchQueueTest {

    @Test
    void spoken_delay_uses_minutes_when_the_wait_is_a_whole_number_of_them() {
        assertEquals("Fridge will turn off in 5 minutes.", Spoken.scheduled("fridge", false, 300));
        assertEquals("Fridge will turn on in 1 minute.", Spoken.scheduled("fridge", true, 60));
        assertEquals("Fridge will turn off in 1 second.", Spoken.scheduled("fridge", false, 1));
        assertEquals("Fridge will turn off in 90 seconds.", Spoken.scheduled("fridge", false, 90));
    }
}
