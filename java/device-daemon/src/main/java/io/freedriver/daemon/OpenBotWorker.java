package io.freedriver.daemon;

import io.freedriver.dispatcher.model.AssignmentState;
import io.freedriver.dispatcher.model.AssignmentView;
import io.freedriver.dispatcher.model.Channel;
import io.freedriver.dispatcher.model.UpdateAssignmentRequest;

import java.time.Duration;
import java.util.UUID;

/**
 * Claims dispatcher jobs over the authenticated device long-poll and routes each
 * prompt to the assignment's agentName. Completes running and succeeded with no
 * grok session id.
 */
final class OpenBotWorker implements DeviceWorker {

    static final String DEVICE_ID = "freedriver-openbot";

    private final DeviceApiClient devices;
    private final AgentRouter router;
    private final DaemonSettings settings;

    OpenBotWorker(DeviceApiClient devices, AgentRouter router, DaemonSettings settings) {
        this.devices = devices;
        this.router = router;
        this.settings = settings;
    }

    @Override
    public void registerOnce() {
        devices.register(settings.hostname(), settings.agents());
    }

    @Override
    public void heartbeatOnce() {
        devices.heartbeat();
    }

    @Override
    public void pollOnce() {
        for (AssignmentView queued : devices.poll()) {
            handle(queued);
        }
    }

    @Override
    public void close() {
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
        try {
            devices.update(current.assignmentId(), new UpdateAssignmentRequest(AssignmentState.RUNNING, null, null));
        } catch (DeviceRejected rejected) {
            return;
        }
        AgentRouter.RouteResult result;
        try {
            result = router.route(current.agentName(), current.prompt(), settings.sessionTimeout());
        } catch (RuntimeException failed) {
            fail(current.assignmentId(), "agent routing failed");
            return;
        }
        AssignmentState state = result != null && result.succeeded() ? AssignmentState.SUCCEEDED : AssignmentState.FAILED;
        String note = result == null ? "agent routing failed" : result.note();
        try {
            devices.update(current.assignmentId(), new UpdateAssignmentRequest(state, null, note));
        } catch (DeviceRejected rejected) {
            // the dispatcher already moved it, often to expired
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

    Duration sessionTimeout() {
        return settings.sessionTimeout();
    }

    private static boolean terminal(AssignmentState state) {
        return state == AssignmentState.SUCCEEDED
                || state == AssignmentState.FAILED
                || state == AssignmentState.EXPIRED;
    }
}
