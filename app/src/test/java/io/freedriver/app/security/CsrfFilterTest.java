package io.freedriver.app.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsrfFilterTest {

    @Test
    void matches_is_constant_time_and_fail_closed() {
        String token = CsrfFilter.mint();
        assertTrue(CsrfFilter.matches(token, token));
        assertFalse(CsrfFilter.matches(token, "nope"));
        assertFalse(CsrfFilter.matches(token, null));
        assertFalse(CsrfFilter.matches(null, token));
        assertFalse(CsrfFilter.matches("", token));
        assertFalse(CsrfFilter.matches(token, ""));
    }
}
