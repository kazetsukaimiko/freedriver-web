package io.freedriver.dispatcher.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One row of {@code GET /api/dispatcher/devices}. The states and times are in
 * {@code docs/device-dispatcher.md}. An empty agent list pairs with an empty {@code stateByAgent}.
 *
 * @param reportedAt the row's last report; null when it never reported
 * @param seenAt     the later of the last report and the last take; null when it has neither
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
