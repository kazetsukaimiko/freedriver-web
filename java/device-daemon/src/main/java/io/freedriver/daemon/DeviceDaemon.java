package io.freedriver.daemon;

import io.freedriver.dispatcher.model.AssignmentState;
import io.freedriver.dispatcher.model.AssignmentView;
import io.freedriver.dispatcher.model.Channel;
import io.freedriver.dispatcher.model.UpdateAssignmentRequest;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/**
 * One poll cycle claims queued work and starts at most one grok at a time.
 * The session timeout kills that child. The 75 second queue deadline stays in JobStore.
 */
final class DeviceDaemon implements AutoCloseable {

    private final DeviceApiClient devices;
    private final GrokLauncher grok;
    private final DaemonSettings settings;
    private volatile RunningGrok active;

    DeviceDaemon(DeviceApiClient devices, GrokLauncher grok, DaemonSettings settings) {
        this.devices = devices;
        this.grok = grok;
        this.settings = settings;
    }

    void registerOnce() {
        devices.register(settings.hostname(), settings.agents());
    }

    void heartbeatOnce() {
        devices.heartbeat();
    }

    void pollOnce() {
        for (AssignmentView queued : devices.poll()) {
            handle(queued);
        }
    }

    Duration sessionTimeout() {
        return settings.sessionTimeout();
    }

    @Override
    public void close() {
        RunningGrok running = active;
        if (running != null) {
            running.kill();
        }
    }

    private void handle(AssignmentView queued) {
        if (queued == null || queued.state() != AssignmentState.QUEUED || queued.assignmentId() == null) {
            return;
        }
        AssignmentView claimed;
        try {
            claimed = devices.claim(queued.assignmentId());
        } catch (DeviceRejected rejected) {
            return;
        }
        if (claimed == null || claimed.state() != AssignmentState.CLAIMED) {
            return;
        }
        AssignmentView current;
        try {
            current = devices.assignment(claimed.assignmentId());
        } catch (DeviceRejected rejected) {
            return;
        }
        if (current == null || terminal(current.state())) {
            return;
        }
        if (claimed.channel() != Channel.TEXT) {
            fail(claimed.assignmentId(), claimed.channel() == null ? "refused channel" : "refused " + claimed.channel().name());
            return;
        }
        if (!canRun(current)) {
            fail(current.assignmentId(), refusal(current));
            return;
        }
        UUID sessionId = UUID.randomUUID();
        Path workDir = settings.workDir() == null ? Path.of(System.getProperty("java.io.tmpdir")) : settings.workDir();
        GrokRequest request = new GrokRequest(
                settings.grokBinary(),
                sessionId,
                current.agentName(),
                settings.allow(),
                current.prompt(),
                workDir);
        RunningGrok running;
        try {
            running = grok.start(request);
        } catch (GrokLaunchException | IllegalArgumentException failed) {
            fail(current.assignmentId(), "grok did not start");
            return;
        }
        active = running;
        try {
            try {
                devices.update(current.assignmentId(), new UpdateAssignmentRequest(AssignmentState.RUNNING, sessionId, null));
            } catch (DeviceRejected rejected) {
                return;
            }
            Outcome outcome = supervise(current.assignmentId(), running);
            if (!outcome.report()) {
                return;
            }
            try {
                devices.update(
                        current.assignmentId(),
                        new UpdateAssignmentRequest(outcome.state(), sessionId, outcome.note()));
            } catch (DeviceRejected rejected) {
                // the dispatcher already moved it, often to expired
            }
        } finally {
            running.close();
            if (active == running) {
                active = null;
            }
        }
    }

    private boolean canRun(AssignmentView current) {
        return current.channel() == Channel.TEXT
                && current.prompt() != null
                && !current.prompt().isBlank()
                && current.agentName() != null
                && settings.agents().contains(current.agentName())
                && current.agentName().matches("[A-Za-z0-9._-]{1,64}")
                && !current.agentName().startsWith("-");
    }

    private static String refusal(AssignmentView current) {
        if (current.channel() != Channel.TEXT) {
            return "refused " + (current.channel() == null ? "channel" : current.channel().name());
        }
        if (current.prompt() == null || current.prompt().isBlank()) {
            return "missing prompt";
        }
        return "agent is not local";
    }

    private void fail(UUID assignmentId, String note) {
        try {
            devices.update(assignmentId, new UpdateAssignmentRequest(AssignmentState.FAILED, null, note));
        } catch (DeviceRejected rejected) {
            // already terminal
        }
    }

    private Outcome supervise(UUID assignmentId, RunningGrok running) {
        long deadline = System.nanoTime() + settings.sessionTimeout().toNanos();
        while (running.alive()) {
            AssignmentView latest;
            try {
                latest = devices.assignment(assignmentId);
            } catch (DeviceRejected rejected) {
                running.kill();
                return Outcome.report(AssignmentState.FAILED, "assignment lost");
            }
            if (latest != null && terminal(latest.state())) {
                running.kill();
                return Outcome.silent();
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                running.kill();
                return Outcome.report(AssignmentState.FAILED, "local timeout");
            }
            long nap = Math.min(settings.watchInterval().toNanos(), remaining);
            if (nap < 1_000_000L) {
                running.kill();
                return Outcome.report(AssignmentState.FAILED, "local timeout");
            }
            try {
                Thread.sleep(nap / 1_000_000L);
            } catch (InterruptedException interrupted) {
                running.kill();
                Thread.currentThread().interrupt();
                return Outcome.report(AssignmentState.FAILED, "interrupted");
            }
        }
        String note = blankToNull(running.output());
        if (running.exitCode() == 0) {
            return Outcome.report(AssignmentState.SUCCEEDED, note);
        }
        return Outcome.report(AssignmentState.FAILED, note == null ? "grok exited " + running.exitCode() : note);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    private static boolean terminal(AssignmentState state) {
        return state == AssignmentState.SUCCEEDED
                || state == AssignmentState.FAILED
                || state == AssignmentState.EXPIRED;
    }

    private record Outcome(boolean report, AssignmentState state, String note) {

        private static Outcome silent() {
            return new Outcome(false, null, null);
        }

        private static Outcome report(AssignmentState state, String note) {
            return new Outcome(true, state, note);
        }
    }
}
