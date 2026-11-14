package io.freedriver.dispatcher.api;

import io.freedriver.dispatcher.model.AssignmentPage;
import io.freedriver.dispatcher.model.DeviceView;
import io.freedriver.dispatcher.model.JobView;
import io.freedriver.dispatcher.model.SubmitJobRequest;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

@Path("/api/dispatcher")
@Produces(MediaType.APPLICATION_JSON)
public interface DispatcherApi {

    @GET
    @Path("/devices")
    @RolesAllowed(Actors.DISPATCHER)
    List<DeviceView> listDevices();

    @GET
    @Path("/jobs")
    @RolesAllowed(Actors.DISPATCHER)
    List<JobView> listJobs();

    @POST
    @Path("/jobs")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(Actors.DISPATCHER)
    JobView submit(@Valid @NotNull SubmitJobRequest body);

    @GET
    @Path("/jobs/{jobId}")
    @RolesAllowed(Actors.DISPATCHER)
    JobView getJob(@PathParam("jobId") UUID jobId);

    /**
     * One page of assignments across all callers, newest first, for the portal's jobs page.
     * {@code limit} is 1 through 200; another value is 400. {@code cursor} is the previous page's {@code next}.
     * The dispatcher's implementation requires {@code dispatcher-admin}. The role is not declared here,
     * because a REST client built from this interface would check it against its own caller.
     */
    @GET
    @Path("/admin/jobs")
    AssignmentPage adminJobs(@QueryParam("limit") @DefaultValue("100") int limit,
                             @QueryParam("cursor") String cursor);
}
