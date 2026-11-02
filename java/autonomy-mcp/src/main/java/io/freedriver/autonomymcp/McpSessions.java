package io.freedriver.autonomymcp;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class McpSessions {

    public enum Status {
        ABSENT,
        UNKNOWN,
        OTHER_SUBJECT,
        MATCH
    }

    private final ConcurrentHashMap<String, String> subjectBySession = new ConcurrentHashMap<>();

    public String open(String subject) {
        String id = UUID.randomUUID().toString();
        subjectBySession.put(id, subject);
        return id;
    }

    public Status check(String sessionId, String subject) {
        if (sessionId == null || sessionId.isBlank()) {
            return Status.ABSENT;
        }
        String bound = subjectBySession.get(sessionId);
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
            subjectBySession.remove(sessionId, subject);
        }
    }
}
