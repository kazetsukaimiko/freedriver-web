package io.freedriver.sms;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PhoneMaskTest {

    @Test
    void keeps_at_most_the_last_two_digits() {
        assertEquals("***00", PhoneMask.mask("+15555550100"));
        assertEquals("***", PhoneMask.mask("00"));
        assertEquals("***00", PhoneMask.mask("100"));
        assertEquals("***", PhoneMask.mask(null));
    }
}
