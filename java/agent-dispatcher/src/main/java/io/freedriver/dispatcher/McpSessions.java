package io.freedriver.dispatcher;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

/**
 * Streamable HTTP session ids name a stored key. Each id is bound to the
 * bearer-token subject that opened it.
 */
@ApplicationScoped
public class McpSessions {

    public enum Status {
        ABSENT,
        UNKNOWN,
        OTHER_SUBJECT,
        MATCH
    }

    private final McpSessionRecords records;

    /** Arc builds the client proxy with this constructor. It does not serve calls. */
    McpSessions() {
        this.records = null;
    }

    @Inject
    McpSessions(McpSessionRecords records) {
        this.records = records;
    }

    public String open(String subject) {
        String id = UUID.randomUUID().toString();
        remember(id, subject);
        return id;
    }

    /** Bind a session id the MCP server already issued. */
    public void remember(String sessionId, String subject) {
        if (sessionId != null && !sessionId.isBlank() && subject != null && !subject.isBlank()) {
            records.insert(sessionId, subject);
        }
    }

    public Status check(String sessionId, String subject) {
        if (sessionId == null || sessionId.isBlank()) {
            return Status.ABSENT;
        }
        String bound = records.subject(sessionId);
        if (bound == null) {
            return Status.UNKNOWN;
        }
        if (!bound.equals(subject)) {
            return Status.OTHER_SUBJECT;
        }
        return Status.MATCH;
    }

    public void close(String sessionId, String subject) {
        if (sessionId != null) {
            records.delete(sessionId, subject);
        }
    }
}
