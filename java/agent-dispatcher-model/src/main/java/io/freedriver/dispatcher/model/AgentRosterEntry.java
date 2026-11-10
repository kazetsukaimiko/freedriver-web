package io.freedriver.dispatcher.model;

/**
 * One agent on one server, as Grok's list_agents tool returns it.
 */
public record AgentRosterEntry(
        String type,
        String name,
        String server,
        String state,
        String activity) {
}
