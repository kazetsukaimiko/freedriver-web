package io.freedriver.dispatcher;

import io.freedriver.dispatcher.api.Actors;
import io.freedriver.dispatcher.api.DispatcherAdminApi;
import io.freedriver.dispatcher.model.AssignmentPage;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Every caller's assignments, for the portal's service account. The role sits here, on the
 * implementation; {@link AllowedClientFilter} pairs that role with the portal client.
 */
@ApplicationScoped
@RolesAllowed(Actors.DISPATCHER_ADMIN)
public class DispatcherAdminResource implements DispatcherAdminApi {

    private final JobStore jobs;

    @Inject
    public DispatcherAdminResource(JobStore jobs) {
        this.jobs = jobs;
    }

    @Override
    public AssignmentPage jobs(int limit, String cursor) {
        return jobs.allAssignments(limit, cursor);
    }
}
