package io.freedriver.dispatcher.model;

import java.time.Instant;

/**
 * One agent on one server, as Grok's list_agents tool returns it. {@code state} is {@code IDLE} or
 * {@code BUSY} as of {@code reportedAt}, {@code UNKNOWN} when the host takes jobs but has not reported
 * within the silence window, or {@code OFFLINE} when it has neither reported nor taken a job within it.
 *
 * @param reportedAt the host's last report; null when it never reported
 * @param seenAt     the later of the host's last report and its last take; null when it has neither
 */
public record AgentRosterEntry(
        String type,
        String name,
        String server,
        String state,
        String activity,
        Instant reportedAt,
        Instant seenAt) {
}
