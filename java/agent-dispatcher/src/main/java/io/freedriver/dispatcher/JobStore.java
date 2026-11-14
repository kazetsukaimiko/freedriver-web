package io.freedriver.dispatcher;

import io.freedriver.dispatcher.model.AgentRosterEntry;
import io.freedriver.dispatcher.model.AssignmentPage;
import io.freedriver.dispatcher.model.AssignmentView;
import io.freedriver.dispatcher.model.DeviceView;
import io.freedriver.dispatcher.model.JobPage;
import io.freedriver.dispatcher.model.JobView;
import io.freedriver.dispatcher.model.ReportedBot;
import io.freedriver.dispatcher.model.SubmitJobRequest;
import io.freedriver.dispatcher.model.UpdateAssignmentRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Dispatch queue. The production bean reads and writes Mongo.
 * There is no in-memory job map beside that database.
 */
public interface JobStore {

    DeviceView register(@NotBlank String deviceId, @NotBlank @Size(max = 253) String hostname,
                        @NotNull @Size(min = 1, max = 32) List<@NotBlank @Size(max = 64) String> agents);

    DeviceView report(@NotBlank String deviceId, @NotBlank @Size(max = 253) String hostname,
                      @NotNull @Size(min = 1, max = 32) List<@Valid @NotNull ReportedBot> bots);

    List<AgentRosterEntry> listAgents();

    String deviceIdFor(@NotBlank String server, @NotBlank String agentName);

    AssignmentView finish(String deviceId, UUID assignmentId, boolean succeeded, @Size(max = 2000) String note);

    DeviceView heartbeat(String deviceId);

    List<DeviceView> listDevices();

    JobView submit(@NotBlank String dispatcherSubject, @Valid @NotNull SubmitJobRequest request, Duration wait);

    JobView submitNow(@NotBlank String dispatcherSubject, @Valid @NotNull SubmitJobRequest request);

    /**
     * Wait until the job is terminal, or until {@code delay} elapses.
     * A timeout leaves the job as it is. The longest delay is two minutes.
     */
    JobView awaitTurn(String dispatcherSubject, UUID jobId, Duration delay);

    JobView getJob(String dispatcherSubject, UUID jobId);

    List<JobView> listJobs(@NotBlank String dispatcherSubject);

    /**
     * One page of jobs this caller submitted, newest first.
     * {@code agentName} and {@code state} are optional. {@code state} is
     * queued, in-progress, or done. {@code limit} is 1 through 50.
     * The rows do not include the prompt.
     */
    JobPage listJobPage(@NotBlank String dispatcherSubject, @Size(min = 1, max = 64) String agentName, String state,
                        @Min(1) @Max(50) int limit, String cursor);

    /**
     * One page of assignments across all callers, newest first by creation time. The rows carry
     * the prompt. {@code limit} is 1 through 200; {@code cursor} is the previous page's {@code next}.
     */
    AssignmentPage allAssignments(int limit, String cursor);

    List<AssignmentView> poll(String deviceId, Duration wait);

    AssignmentView assignment(String deviceId, UUID assignmentId);

    AssignmentView claim(String deviceId, UUID assignmentId);

    AssignmentView update(String deviceId, UUID assignmentId, @Valid @NotNull UpdateAssignmentRequest request);

}
