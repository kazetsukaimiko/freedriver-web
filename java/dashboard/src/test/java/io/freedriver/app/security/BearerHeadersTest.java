package io.freedriver.app.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BearerHeadersTest {

    @Test
    void bearer_needs_a_token() {
        assertTrue(BearerHeaders.hasBearer("Bearer abc"));
        assertTrue(BearerHeaders.hasBearer("bearer abc"));
        assertFalse(BearerHeaders.hasBearer("Bearer "));
        assertFalse(BearerHeaders.hasBearer("Basic abc"));
        assertFalse(BearerHeaders.hasBearer(null));
    }
}
