package io.freedriver.dispatcher;

import io.freedriver.dispatcher.api.DeviceApi;
import io.freedriver.dispatcher.model.AssignmentView;
import io.freedriver.dispatcher.model.DeviceView;
import io.freedriver.dispatcher.model.RegisterDeviceRequest;
import io.freedriver.dispatcher.model.UpdateAssignmentRequest;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class DeviceResource implements DeviceApi {

    private final JobStore jobs;
    private final SecurityIdentity identity;
    private final Duration pollTimeout;

    @Inject
    public DeviceResource(
            JobStore jobs,
            SecurityIdentity identity,
            @ConfigProperty(name = "freedriver.dispatcher.poll-timeout", defaultValue = "25s") Duration pollTimeout) {
        this.jobs = jobs;
        this.identity = identity;
        this.pollTimeout = pollTimeout;
    }

    @Override
    public DeviceView register(RegisterDeviceRequest body) {
        return jobs.register(CallerIds.deviceId(identity), body.hostname(), body.agents());
    }

    @Override
    public DeviceView heartbeat() {
        return jobs.heartbeat(CallerIds.deviceId(identity));
    }

    @Override
    @Blocking
    public List<AssignmentView> pollAssignments() {
        return jobs.poll(CallerIds.deviceId(identity), pollTimeout);
    }

    @Override
    public AssignmentView assignment(UUID assignmentId) {
        return jobs.assignment(CallerIds.deviceId(identity), assignmentId);
    }

    @Override
    public AssignmentView claim(UUID assignmentId) {
        return jobs.claim(CallerIds.deviceId(identity), assignmentId);
    }

    @Override
    public AssignmentView update(UUID assignmentId, UpdateAssignmentRequest body) {
        return jobs.update(CallerIds.deviceId(identity), assignmentId, body);
    }
}
