package io.freedriver.dispatcher.model;

import java.time.Instant;
import java.util.List;

public record DeviceView(String deviceId, String hostname, List<String> agents, Instant lastHeartbeat) {
}
