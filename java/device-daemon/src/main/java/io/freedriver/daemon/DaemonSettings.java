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
        Path workDir,
        WorkerMode mode,
        Path automationDir) {

    DaemonSettings(
            String hostname,
            List<String> agents,
            List<String> allow,
            String grokBinary,
            Duration sessionTimeout,
            Duration watchInterval,
            Path workDir) {
        this(hostname, agents, allow, grokBinary, sessionTimeout, watchInterval, workDir, WorkerMode.GROK, null);
    }

    /** OpenBot mode: the connector reads the Local scripts API from {@code automationDir}. */
    static DaemonSettings openBot(String hostname, List<String> agents, Path automationDir) {
        return new DaemonSettings(
                hostname, agents, List.of(), null, DaemonConfig.DEFAULT_SESSION_TIMEOUT, DaemonConfig.DEFAULT_WATCH,
                null, WorkerMode.OPENBOT, automationDir);
    }

    DaemonSettings {
        if (hostname == null || hostname.isBlank()) {
            throw new IllegalArgumentException("hostname is required");
        }
        if (agents == null || agents.isEmpty()) {
            throw new IllegalArgumentException("agents are required");
        }
        agents = List.copyOf(agents);
        allow = allow == null ? List.of() : List.copyOf(allow);
        mode = mode == null ? WorkerMode.GROK : mode;
        if (mode == WorkerMode.GROK && (grokBinary == null || grokBinary.isBlank())) {
            throw new IllegalArgumentException("grok binary is required");
        }
        if (mode == WorkerMode.OPENBOT && automationDir == null) {
            throw new IllegalArgumentException("automationDir is required");
        }
        if (sessionTimeout == null || sessionTimeout.isZero() || sessionTimeout.isNegative()) {
            throw new IllegalArgumentException("session timeout must be positive");
        }
        if (watchInterval == null || watchInterval.isZero() || watchInterval.isNegative()) {
            throw new IllegalArgumentException("watch interval must be positive");
        }
    }
}
