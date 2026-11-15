package io.freedriver.dispatcher;

import io.freedriver.dispatcher.api.Actors;
import io.freedriver.dispatcher.api.HostApi;
import io.freedriver.dispatcher.model.AssignmentView;
import io.freedriver.dispatcher.model.DeviceView;
import io.freedriver.dispatcher.model.FinishRequest;
import io.freedriver.dispatcher.model.HostReport;
import io.freedriver.dispatcher.model.StartRequest;
import io.freedriver.dispatcher.model.TakeRequest;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * The host API over {@link JobStore}. The role sits here, on the implementation;
 * {@link AllowedClientFilter} pairs it with the device client. The device id comes from the token.
 */
@ApplicationScoped
@RolesAllowed(Actors.DEVICE)
public class HostResource implements HostApi {

    private final JobStore jobs;
    private final SecurityIdentity identity;

    @Inject
    public HostResource(JobStore jobs, SecurityIdentity identity) {
        this.jobs = jobs;
        this.identity = identity;
    }

    @Override
    public DeviceView report(HostReport body) {
        return jobs.report(JobCallers.deviceId(identity), body.hostname(), body.bots());
    }

    @Override
    public List<AssignmentView> take(TakeRequest body) {
        return jobs.take(JobCallers.deviceId(identity),
                body.holding() == null ? List.of() : body.holding(), Duration.ofSeconds(body.waitSeconds()));
    }

    @Override
    public AssignmentView start(UUID assignmentId, StartRequest body) {
        return jobs.start(JobCallers.deviceId(identity), assignmentId, body.deliveryRef());
    }

    @Override
    public AssignmentView finish(UUID assignmentId, FinishRequest body) {
        return jobs.finish(JobCallers.deviceId(identity), assignmentId, body.succeeded(), body.note());
    }
}
