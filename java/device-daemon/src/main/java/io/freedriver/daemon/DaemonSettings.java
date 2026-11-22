package io.freedriver.daemon;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

record DaemonSettings(
        String hostname,
        List<String> agents,
        Duration watchInterval,
        Path automationDir) {

    /** The Agent Bridge reads the Local scripts API from {@code automationDir}. */
    static DaemonSettings openBot(String hostname, List<String> agents, Path automationDir) {
        return new DaemonSettings(hostname, agents, DaemonConfig.DEFAULT_WATCH, automationDir);
    }

    DaemonSettings {
        if (hostname == null || hostname.isBlank()) {
            throw new IllegalArgumentException("hostname is required");
        }
        if (agents == null || agents.isEmpty()) {
            throw new IllegalArgumentException("agents are required");
        }
        agents = List.copyOf(agents);
        if (automationDir == null) {
            throw new IllegalArgumentException("automationDir is required");
        }
        if (watchInterval == null || watchInterval.isZero() || watchInterval.isNegative()) {
            throw new IllegalArgumentException("watch interval must be positive");
        }
    }
}
