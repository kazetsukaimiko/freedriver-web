package io.freedriver.dispatcher;

import io.freedriver.dispatcher.api.DispatcherApi;
import io.freedriver.dispatcher.model.DeviceView;
import io.freedriver.dispatcher.model.JobView;
import io.freedriver.dispatcher.model.SubmitJobRequest;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class DispatcherResource implements DispatcherApi {

    private final JobStore jobs;
    private final SecurityIdentity identity;
    private final Duration submitTimeout;

    @Inject
    public DispatcherResource(
            JobStore jobs,
            SecurityIdentity identity,
            DispatcherConfig config) {
        this.jobs = jobs;
        this.identity = identity;
        this.submitTimeout = config.submitTimeout();
    }

    @Override
    public List<DeviceView> listDevices() {
        return jobs.listDevices();
    }

    @Override
    public List<JobView> listJobs() {
        return jobs.listJobs(JobCallers.subject(identity));
    }

    @Override
    @Blocking
    public JobView submit(SubmitJobRequest body) {
        return jobs.submit(JobCallers.subject(identity), body, submitTimeout);
    }

    @Override
    public JobView getJob(UUID jobId) {
        return jobs.getJob(JobCallers.subject(identity), jobId);
    }
}
