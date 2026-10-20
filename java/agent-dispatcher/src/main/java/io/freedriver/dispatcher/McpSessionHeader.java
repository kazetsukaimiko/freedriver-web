package io.freedriver.dispatcher;

import jakarta.enterprise.context.RequestScoped;

/**
 * Session id earned by this request. {@link McpSessionHeaderFilter} writes the header
 * so the resource can return a typed body.
 */
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
