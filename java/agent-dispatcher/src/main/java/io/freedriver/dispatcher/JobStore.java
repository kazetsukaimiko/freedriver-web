package io.freedriver.dispatcher;

import io.freedriver.dispatcher.model.AgentRosterEntry;
import io.freedriver.dispatcher.model.AssignmentView;
import io.freedriver.dispatcher.model.DeviceView;
import io.freedriver.dispatcher.model.JobView;
import io.freedriver.dispatcher.model.ReportedBot;
import io.freedriver.dispatcher.model.SubmitJobRequest;
import io.freedriver.dispatcher.model.UpdateAssignmentRequest;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Dispatch queue. The production bean reads and writes Mongo.
 * There is no in-memory job map beside that database.
 */
public interface JobStore {

    DeviceView register(String deviceId, String hostname, List<String> agents);

    DeviceView report(String deviceId, String hostname, List<ReportedBot> bots);

    List<AgentRosterEntry> listAgents();

    String deviceIdFor(String server, String agentName);

    AssignmentView finish(String deviceId, UUID assignmentId, boolean succeeded, String note);

    DeviceView heartbeat(String deviceId);

    List<DeviceView> listDevices();

    JobView submit(String dispatcherSubject, SubmitJobRequest request, Duration wait);

    JobView submitNow(String dispatcherSubject, SubmitJobRequest request);

    /**
     * Wait until the job is terminal, or until {@code delay} elapses.
     * A timeout leaves the job as it is. The longest delay is two minutes.
     */
    JobView awaitTurn(String dispatcherSubject, UUID jobId, Duration delay);

    JobView getJob(String dispatcherSubject, UUID jobId);

    List<JobView> listJobs(String dispatcherSubject);

    List<AssignmentView> poll(String deviceId, Duration wait);

    AssignmentView assignment(String deviceId, UUID assignmentId);

    AssignmentView claim(String deviceId, UUID assignmentId);

    AssignmentView update(String deviceId, UUID assignmentId, UpdateAssignmentRequest request);

    /** Distinct jobs currently stored. Tests use this to see that a rejected call wrote nothing. */
    int jobCount();
}
