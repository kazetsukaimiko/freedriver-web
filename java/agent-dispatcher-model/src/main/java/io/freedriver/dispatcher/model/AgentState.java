package io.freedriver.dispatcher.model;

/**
 * The four states {@code list_agents} returns in {@link AgentRosterEntry#state}. JSON is the enum name:
 * {@code IDLE}, {@code BUSY}, {@code UNKNOWN}, {@code OFFLINE}.
 */
public enum AgentState {
    IDLE,
    BUSY,
    UNKNOWN,
    OFFLINE
}
