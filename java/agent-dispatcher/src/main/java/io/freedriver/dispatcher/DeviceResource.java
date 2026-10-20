package io.freedriver.dispatcher;

import io.freedriver.dispatcher.api.DeviceApi;
import io.freedriver.dispatcher.model.AssignmentView;
import io.freedriver.dispatcher.model.DeviceView;
import io.freedriver.dispatcher.model.RegisterDeviceRequest;
import io.freedriver.dispatcher.model.UpdateAssignmentRequest;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
@Path("/api/device")
public class DeviceResource implements DeviceApi {

    private final JobStore jobs;
    private final CallerIdentity caller;
    private final Duration pollTimeout;

    @Inject
    public DeviceResource(
            JobStore jobs,
            CallerIdentity caller,
            @ConfigProperty(name = "freedriver.dispatcher.poll-timeout", defaultValue = "25s") Duration pollTimeout) {
        this.jobs = jobs;
        this.caller = caller;
        this.pollTimeout = pollTimeout;
    }

    @Override
    public DeviceView register(RegisterDeviceRequest body) {
        return jobs.register(caller.deviceId(), body.hostname(), body.agents());
    }

    @Override
    public DeviceView heartbeat() {
        return jobs.heartbeat(caller.deviceId());
    }

    @Override
    @Blocking
    public List<AssignmentView> pollAssignments() {
        return jobs.poll(caller.deviceId(), pollTimeout);
    }

    @Override
    public AssignmentView claim(UUID assignmentId) {
        return jobs.claim(caller.deviceId(), assignmentId);
    }

    @Override
    public AssignmentView update(UUID assignmentId, UpdateAssignmentRequest body) {
        return jobs.update(caller.deviceId(), assignmentId, body);
    }
}
