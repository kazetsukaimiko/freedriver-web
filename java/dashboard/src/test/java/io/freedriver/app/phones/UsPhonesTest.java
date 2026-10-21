package io.freedriver.app.phones;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsPhonesTest {

    @Test
    void the_router_shapes_become_the_same_canonical_number() {
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
            assertEquals(canonical, UsPhones.canonical(raw).orElseThrow(), raw);
        }
        assertTrue(UsPhones.canonical("55555501234").isEmpty());
        assertTrue(UsPhones.canonical("call 5555550123").isEmpty());
        assertEquals("(555) 555-0123", UsPhones.display(canonical));
    }
}
