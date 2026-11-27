package io.freedriver.dispatcher.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * One device's assignment. {@code channel} and {@code prompt} belong to this
 * assignment only. Callers must already have scoped the device or the dispatcher.
 * {@code deliveryRef} is the host's receipt for the delivery that carries the job, as
 * {@link StartRequest} describes; it is set by the host's start and null until then.
 *
 * <p>{@code agentSessionId} is the session of the agent that works on the job, whichever agent
 * that is. Its JSON property is {@code agentSessionId}, the same name.
 *
 * <p>{@code finishBy} is the job's finish deadline. It is set only for a job the host started with
 * {@link StartRequest#fireAndForget()} true, and is null otherwise: before the start, and for a
 * watch-mode job. The first accepted start fixes it at that start plus the dispatcher's
 * finish-within setting (10 minutes by default); a repeated start leaves it unchanged. It
 * serializes as an ISO-8601 instant, or as JSON null when unset.
 *
 * <p>A fire-and-forget job that nobody finishes by {@code finishBy} becomes
 * {@link AssignmentState#FAILED}, and its {@code note} says so, for example
 * {@code no finish within 10 minutes}. A host's finish after the deadline gets that stored FAILED
 * row with status 200, as a finish of any already finished job does.
 */
public record AssignmentView(
        UUID assignmentId,
        UUID jobId,
        String deviceId,
        String agentName,
        Channel channel,
        String prompt,
        AssignmentState state,
        UUID agentSessionId,
        String note,
        @JsonProperty("on_busy") OnBusy onBusy,
        String deliveryRef,
        Instant finishBy) {

    /**
     * An assignment with no finish deadline. It keeps callers written before {@code finishBy}
     * compiling; #736 moves them to the canonical constructor and deletes this one. JSON never
     * binds through it.
     */
    @JsonCreator(mode = JsonCreator.Mode.DISABLED)
    public AssignmentView(
            UUID assignmentId,
            UUID jobId,
            String deviceId,
            String agentName,
            Channel channel,
            String prompt,
            AssignmentState state,
            UUID agentSessionId,
            String note,
            OnBusy onBusy,
            String deliveryRef) {
        this(assignmentId, jobId, deviceId, agentName, channel, prompt, state, agentSessionId, note, onBusy,
                deliveryRef, null);
    }

    /**
     * {@link #agentSessionId()} under its previous name. It keeps callers written before the
     * rename compiling until they move to {@code agentSessionId()}.
     *
     * @deprecated use {@link #agentSessionId()}.
     */
    @Deprecated(forRemoval = true)
    public UUID grokSessionId() {
        return agentSessionId;
    }
}
