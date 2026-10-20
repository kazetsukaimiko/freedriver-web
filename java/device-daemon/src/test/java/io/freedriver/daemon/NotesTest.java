package io.freedriver.daemon;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class NotesTest {

    @Test
    void redacts_secret_token_and_credential_file_before_the_cap() {
        String secret = "SECRETVALUE12345678";
        String token = "test-access-token-value";
        String raw = "prefix-" + secret + "-suffix";
        String straddling = "a".repeat(1990) + secret + "zzzz";

        String cleaned = Notes.forUpdate(
                "saw " + secret + " and " + token + " and " + raw,
                secret,
                token,
                raw);
        String capped = Notes.forUpdate(straddling, secret, token, raw);

        assertEquals("saw [redacted] and [redacted] and [redacted]", cleaned);
        assertEquals(2000, capped.length());
        assertFalse(capped.contains(secret));
        assertFalse(capped.contains("SECRETVALUE"));
        assertNull(Notes.forUpdate(null, secret));
    }
}
