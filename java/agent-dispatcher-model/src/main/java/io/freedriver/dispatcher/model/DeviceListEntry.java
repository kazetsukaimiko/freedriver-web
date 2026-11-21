package io.freedriver.dispatcher.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One host on {@code GET /api/dispatcher/devices}. {@code stateByAgent} and {@code reportedAt} and
 * {@code seenAt} are the same states and instants {@code list_agents} returns for that host, from
 * {@code freedriver.dispatcher.host-silent-after}. A quiet host is {@code UNKNOWN} or {@code OFFLINE}
 * rather than its last stored activity. {@code lastHeartbeat} stays the report time. A host with no
 * agents has an empty {@code stateByAgent}.
 *
 * @param reportedAt the host's last report; null when it never reported
 * @param seenAt     the later of the host's last report and its last take; null when it has neither
 */
public record DeviceListEntry(
        String deviceId,
        String hostname,
        List<String> agents,
        Instant lastHeartbeat,
        Instant reportedAt,
        Instant seenAt,
        Map<String, AgentState> stateByAgent) {
}
