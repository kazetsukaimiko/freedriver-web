package io.freedriver.mcp;

import io.smallrye.mutiny.Uni;

/**
 * Keys that bind an MCP session id to the subject that opened it. The id names the key, and every
 * replica reads the same key. The calls do not block, because the session filter runs on the event loop.
 */
public interface McpSessionRecords {

    Uni<Void> insert(String sessionId, String subject);

    /** The bound subject, or null when the id is not stored. */
    Uni<String> subject(String sessionId);

    /** Removes the key when it is bound to {@code subject}. */
    Uni<Void> delete(String sessionId, String subject);
}
