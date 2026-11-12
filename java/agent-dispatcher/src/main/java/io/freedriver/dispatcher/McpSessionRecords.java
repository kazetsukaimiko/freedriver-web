package io.freedriver.dispatcher;

/**
 * Keys that bind an MCP session id to the bearer subject that opened it.
 * The id names the key. Callers read it again after each write.
 */
interface McpSessionRecords {

    void insert(String sessionId, String subject);

    /** The bound subject, or null when the id is not stored. */
    String subject(String sessionId);

    /** Removes the key when it is bound to {@code subject}. */
    void delete(String sessionId, String subject);
}
