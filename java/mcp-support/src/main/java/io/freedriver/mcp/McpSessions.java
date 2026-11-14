package io.freedriver.mcp;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Binds each MCP session id the server issues to the subject that opened it. */
@ApplicationScoped
public class McpSessions {

    public enum Status {
        ABSENT,
        UNKNOWN,
        OTHER_SUBJECT,
        MATCH
    }

    private final McpSessionRecords records;

    @Inject
    public McpSessions(McpSessionRecords records) {
        this.records = records;
    }

    /** Binds a session id the MCP server issued. */
    public Uni<Void> remember(String sessionId, String subject) {
        if (sessionId == null || sessionId.isBlank() || subject == null || subject.isBlank()) {
            return Uni.createFrom().voidItem();
        }
        return records.insert(sessionId, subject);
    }

    public Uni<Status> check(String sessionId, String subject) {
        if (sessionId == null || sessionId.isBlank()) {
            return Uni.createFrom().item(Status.ABSENT);
        }
        return records.subject(sessionId).map(bound -> {
            if (bound == null) {
                return Status.UNKNOWN;
            }
            return bound.equals(subject) ? Status.MATCH : Status.OTHER_SUBJECT;
        });
    }

    public Uni<Void> close(String sessionId, String subject) {
        if (sessionId == null) {
            return Uni.createFrom().voidItem();
        }
        return records.delete(sessionId, subject);
    }
}
