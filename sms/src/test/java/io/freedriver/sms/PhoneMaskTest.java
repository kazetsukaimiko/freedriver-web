package io.freedriver.sms;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PhoneMaskTest {

    @Test
    void keeps_at_most_the_last_four_digits() {
        assertEquals("***0100", PhoneMask.mask("+15555550100"));
        assertEquals("***", PhoneMask.mask("0100"));
        assertEquals("***", PhoneMask.mask(null));
    }
}
