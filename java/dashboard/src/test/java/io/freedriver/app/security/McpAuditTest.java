package io.freedriver.app.security;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpAuditTest {

    @Test
    void line_uses_dashes_and_omits_secrets() {
        Instant when = Instant.parse("2026-10-28T12:00:00Z");
        String line = McpAudit.line(new McpAudit.Event(when, "sub-1", "freedriver-grok-mcp", null, null, null, "allowed"));
        assertEquals(
                "audit mcp when=2026-10-28T12:00:00Z sub=sub-1 azp=freedriver-grok-mcp tool=- instance=- appliance=- result=allowed",
                line);
        assertFalse(line.contains("Bearer"));
        assertFalse(line.contains("q_session"));
        assertFalse(line.contains("cookie"));
        assertTrue(McpGateFilter.result(204).equals("allowed"));
        assertEquals("denied", McpGateFilter.result(401));
        assertEquals("denied", McpGateFilter.result(403));
        assertEquals("rate-limited", McpGateFilter.result(429));
        assertEquals("error", McpGateFilter.result(500));
    }
}
