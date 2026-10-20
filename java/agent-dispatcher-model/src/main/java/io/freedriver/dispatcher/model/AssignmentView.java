package io.freedriver.dispatcher.model;

import java.util.UUID;

public record AssignmentView(
        UUID assignmentId,
        UUID jobId,
        String deviceId,
        String agentName,
        AssignmentState state,
        UUID grokSessionId,
        String note) {
}
