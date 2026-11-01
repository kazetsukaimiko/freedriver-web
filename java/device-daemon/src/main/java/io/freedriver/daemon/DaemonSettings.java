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
        List<String> router) {

    DaemonSettings(
            String hostname,
            List<String> agents,
            List<String> allow,
            String grokBinary,
            Duration sessionTimeout,
            Duration watchInterval,
            Path workDir) {
        this(hostname, agents, allow, grokBinary, sessionTimeout, watchInterval, workDir, WorkerMode.GROK, List.of());
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
        router = router == null ? List.of() : List.copyOf(router);
        if (mode == WorkerMode.GROK && (grokBinary == null || grokBinary.isBlank())) {
            throw new IllegalArgumentException("grok binary is required");
        }
        if (mode == WorkerMode.OPENBOT && router.isEmpty()) {
            throw new IllegalArgumentException("router is required");
        }
        if (sessionTimeout == null || sessionTimeout.isZero() || sessionTimeout.isNegative()) {
            throw new IllegalArgumentException("session timeout must be positive");
        }
        if (watchInterval == null || watchInterval.isZero() || watchInterval.isNegative()) {
            throw new IllegalArgumentException("watch interval must be positive");
        }
    }
}
