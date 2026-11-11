package io.freedriver.app.jobs;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

/**
 * Portal-admin list of dispatch jobs copied into {@code agent-jobs}.
 * The session is the same cookie session as {@code /api/phones}.
 */
@Path("/api/jobs")
@Produces(MediaType.APPLICATION_JSON)
public interface AgentJobsApi {

    @GET
    @RolesAllowed("portal-admin")
    JobList list();

    record JobList(List<JobRow> jobs) {
    }

    record JobRow(
            String assignmentId,
            String jobId,
            String prompt,
            String channel,
            String status,
            String note,
            Map<String, Object> document) {
    }
}
