package io.freedriver.app.jobs;

import io.freedriver.dispatcher.model.AssignmentView;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * Portal-admin list of every dispatch assignment, newest first, from the dispatcher.
 * The session is the same cookie session as {@code /api/phones}.
 */
@Path("/api/jobs")
@Produces(MediaType.APPLICATION_JSON)
public interface AgentJobsApi {

    @GET
    @RolesAllowed("portal-admin")
    JobList list();

    record JobList(List<AssignmentView> jobs) {
    }
}
