package io.freedriver.sms.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsPhoneNumbersTest {

    @Test
    void dashboardShapesBecomeTheStoredForm() {
        String canonical = "+15555550123";
        for (String raw : new String[] {
                "5555550123",
                "555-555-0123",
                "(555) 555-0123",
                "555.555.0123",
                "555 555 0123",
                "1-555-555-0123",
                "+1 555 555 0123",
                "+15555550123"
        }) {
            assertEquals(canonical, UsPhoneNumbers.canonical(raw).orElseThrow(), raw);
            assertTrue(UsPhoneNumbers.isCanonical(UsPhoneNumbers.canonical(raw).orElseThrow()), raw);
        }
    }

    @Test
    void routerShapesBecomeTheStoredForm() {
        String canonical = "+11234567890";
        for (String raw : new String[] {
                "123-456-7890",
                "1234567890",
                "(123) 456-7890",
                "123.456.7890",
                "123 456 7890",
                "1-123-456-7890",
                "+1 123 456 7890",
                "+11234567890"
        }) {
            assertEquals(canonical, UsPhoneNumbers.canonical(raw).orElseThrow(), raw);
        }
    }

    @Test
    void nonMatchesNullAndACanonicalValue() {
        assertTrue(UsPhoneNumbers.canonical("55555501234").isEmpty());
        assertTrue(UsPhoneNumbers.canonical("call 5555550123").isEmpty());
        assertTrue(UsPhoneNumbers.canonical(null).isEmpty());
        assertFalse(UsPhoneNumbers.isCanonical(null));
        assertEquals("+15555550123", UsPhoneNumbers.canonical("+15555550123").orElseThrow());
        assertTrue(UsPhoneNumbers.isCanonical("+15555550123"));
        assertFalse(UsPhoneNumbers.isCanonical("+15555550123x"));
    }
}
