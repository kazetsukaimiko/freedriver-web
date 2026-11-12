package io.freedriver.autonomymcp;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.concurrent.ConcurrentHashMap;

/** Session rows for the test profile. */
@Alternative
@Priority(1)
@ApplicationScoped
public class MemorySessionRecords implements McpSessionRecords {

    private final ConcurrentHashMap<String, String> subjectBySession = new ConcurrentHashMap<>();

    @Override
    public void insert(String sessionId, String subject) {
        subjectBySession.put(sessionId, subject);
    }

    @Override
    public String subject(String sessionId) {
        return subjectBySession.get(sessionId);
    }

    @Override
    public void delete(String sessionId, String subject) {
        subjectBySession.remove(sessionId, subject);
    }
}
