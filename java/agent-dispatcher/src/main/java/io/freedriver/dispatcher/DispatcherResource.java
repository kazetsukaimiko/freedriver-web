package io.freedriver.dispatcher;

import io.freedriver.dispatcher.api.DispatcherApi;
import io.freedriver.dispatcher.model.DeviceView;
import io.freedriver.dispatcher.model.JobView;
import io.freedriver.dispatcher.model.SubmitJobRequest;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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
            @ConfigProperty(name = "freedriver.dispatcher.submit-timeout", defaultValue = "75s") Duration submitTimeout) {
        this.jobs = jobs;
        this.identity = identity;
        this.submitTimeout = submitTimeout;
    }

    @Override
    public List<DeviceView> listDevices() {
        return jobs.listDevices();
    }

    @Override
    @Blocking
    public JobView submit(SubmitJobRequest body) {
        return jobs.submit(CallerIds.subject(identity), body, submitTimeout);
    }

    @Override
    public JobView getJob(UUID jobId) {
        return jobs.getJob(CallerIds.subject(identity), jobId);
    }
}
