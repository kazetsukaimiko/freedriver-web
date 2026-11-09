package io.freedriver.dispatcher.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record DeviceView(
        String deviceId,
        String hostname,
        List<String> agents,
        Instant lastHeartbeat,
        Map<String, String> activity) {
}
