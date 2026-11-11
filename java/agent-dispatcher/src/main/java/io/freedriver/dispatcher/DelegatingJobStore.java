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
 * CDI beans extend this so Arc can build a client proxy. The empty constructor
 * is only for that proxy. The real instance is built with a {@link DocumentJobStore}.
 */
class DelegatingJobStore implements JobStore {

    private final DocumentJobStore store;

    DelegatingJobStore() {
        this.store = null;
    }

    DelegatingJobStore(DocumentJobStore store) {
        this.store = store;
    }

    @Override
    public DeviceView register(String deviceId, String hostname, List<String> agents) {
        return store.register(deviceId, hostname, agents);
    }

    @Override
    public DeviceView report(String deviceId, String hostname, List<ReportedBot> bots) {
        return store.report(deviceId, hostname, bots);
    }

    @Override
    public List<AgentRosterEntry> listAgents() {
        return store.listAgents();
    }

    @Override
    public String deviceIdFor(String server, String agentName) {
        return store.deviceIdFor(server, agentName);
    }

    @Override
    public AssignmentView finish(String deviceId, UUID assignmentId, boolean succeeded, String note) {
        return store.finish(deviceId, assignmentId, succeeded, note);
    }

    @Override
    public DeviceView heartbeat(String deviceId) {
        return store.heartbeat(deviceId);
    }

    @Override
    public List<DeviceView> listDevices() {
        return store.listDevices();
    }

    @Override
    public JobView submit(String dispatcherSubject, SubmitJobRequest request, Duration wait) {
        return store.submit(dispatcherSubject, request, wait);
    }

    @Override
    public JobView submitNow(String dispatcherSubject, SubmitJobRequest request) {
        return store.submitNow(dispatcherSubject, request);
    }

    @Override
    public JobView awaitTurn(String dispatcherSubject, UUID jobId, Duration delay) {
        return store.awaitTurn(dispatcherSubject, jobId, delay);
    }

    @Override
    public JobView getJob(String dispatcherSubject, UUID jobId) {
        return store.getJob(dispatcherSubject, jobId);
    }

    @Override
    public List<JobView> listJobs(String dispatcherSubject) {
        return store.listJobs(dispatcherSubject);
    }

    @Override
    public List<AssignmentView> poll(String deviceId, Duration wait) {
        return store.poll(deviceId, wait);
    }

    @Override
    public AssignmentView assignment(String deviceId, UUID assignmentId) {
        return store.assignment(deviceId, assignmentId);
    }

    @Override
    public AssignmentView claim(String deviceId, UUID assignmentId) {
        return store.claim(deviceId, assignmentId);
    }

    @Override
    public AssignmentView update(String deviceId, UUID assignmentId, UpdateAssignmentRequest request) {
        return store.update(deviceId, assignmentId, request);
    }

    @Override
    public int jobCount() {
        return store.jobCount();
    }
}
