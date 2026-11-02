package io.freedriver.autonomymcp;

import jakarta.enterprise.context.RequestScoped;

@RequestScoped
public class McpSessionHeader {

    private String sessionId;

    public void set(String sessionId) {
        this.sessionId = sessionId;
    }

    public String get() {
        return sessionId;
    }
}
