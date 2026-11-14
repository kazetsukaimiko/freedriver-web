package io.freedriver.autonomymcp;

import io.freedriver.mcp.McpAudit;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The shared audit line from mcp-support, and the result names the tools write into it. */
class McpAuditTest {

    @Test
    void line_uses_dashes_and_omits_secrets() {
        Instant when = Instant.parse("2026-10-28T12:00:00Z");
        String line = McpAudit.line(new McpAudit.Event(when, "sub-1", "freedriver-grok-mcp", "debug", null, null, "allowed"));
        assertEquals(
                "audit mcp when=2026-10-28T12:00:00Z sub=sub-1 azp=freedriver-grok-mcp tool=debug instance=- appliance=- result=allowed",
                line);
        assertFalse(line.contains("Bearer"));
        assertEquals("allowed", AutonomyTools.resultOf(204));
        assertEquals("denied", AutonomyTools.resultOf(401));
        assertEquals("denied", AutonomyTools.resultOf(403));
        assertEquals("rate-limited", AutonomyTools.resultOf(429));
        assertEquals("error", AutonomyTools.resultOf(500));
    }
}
