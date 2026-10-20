package io.freedriver.daemon;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

record DaemonSettings(
        String hostname,
        List<String> agents,
        List<String> allow,
        String grokBinary,
        Duration sessionTimeout,
        Duration watchInterval,
        Path workDir) {

    DaemonSettings {
        if (hostname == null || hostname.isBlank()) {
            throw new IllegalArgumentException("hostname is required");
        }
        if (agents == null || agents.isEmpty()) {
            throw new IllegalArgumentException("agents are required");
        }
        agents = List.copyOf(agents);
        allow = allow == null ? List.of() : List.copyOf(allow);
        if (grokBinary == null || grokBinary.isBlank()) {
            throw new IllegalArgumentException("grok binary is required");
        }
        if (sessionTimeout == null || sessionTimeout.isZero() || sessionTimeout.isNegative()) {
            throw new IllegalArgumentException("session timeout must be positive");
        }
        if (watchInterval == null || watchInterval.isZero() || watchInterval.isNegative()) {
            throw new IllegalArgumentException("watch interval must be positive");
        }
    }
}
