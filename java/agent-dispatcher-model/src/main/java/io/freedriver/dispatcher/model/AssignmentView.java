package io.freedriver.dispatcher.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * One device's assignment. {@code channel} and {@code prompt} belong to this
 * assignment only. Callers must already have scoped the device or the dispatcher.
 */
public record AssignmentView(
        UUID assignmentId,
        UUID jobId,
        String deviceId,
        String agentName,
        Channel channel,
        String prompt,
        AssignmentState state,
        UUID grokSessionId,
        String note,
        @JsonProperty("on_busy") OnBusy onBusy) {
}
