package io.freedriver.app.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpPathsTest {

    @Test
    void matches_the_mcp_path_only() {
        assertTrue(McpPaths.matches("/autonomy/mcp"));
        assertTrue(McpPaths.matches("autonomy/mcp"));
        assertTrue(McpPaths.matches("/autonomy/mcp/"));
        assertTrue(McpPaths.matches("/autonomy/mcp/tools"));
        assertFalse(McpPaths.matches("/autonomy"));
        assertFalse(McpPaths.matches("/api/appliances"));
        assertFalse(McpPaths.matches("/mcp"));
        assertFalse(McpPaths.matches("/dispatch/mcp"));
    }

    @Test
    void session_cookie_is_q_session() {
        assertTrue(McpPaths.hasSessionCookie("q_session=abc"));
        assertTrue(McpPaths.hasSessionCookie("freedriver-csrf=x; q_session_at=abc"));
        assertFalse(McpPaths.hasSessionCookie("freedriver-csrf=x"));
        assertFalse(McpPaths.hasSessionCookie(null));
        assertFalse(McpPaths.hasSessionCookie(""));
    }

    @Test
    void bearer_needs_a_token() {
        assertTrue(McpPaths.hasBearer("Bearer abc"));
        assertTrue(McpPaths.hasBearer("bearer abc"));
        assertFalse(McpPaths.hasBearer("Bearer "));
        assertFalse(McpPaths.hasBearer("Basic abc"));
        assertFalse(McpPaths.hasBearer(null));
    }
}
