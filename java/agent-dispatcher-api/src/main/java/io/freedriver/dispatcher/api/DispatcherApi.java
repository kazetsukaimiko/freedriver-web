package io.freedriver.dispatcher.api;

import io.freedriver.dispatcher.model.DeviceView;
import io.freedriver.dispatcher.model.JobView;
import io.freedriver.dispatcher.model.SubmitJobRequest;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
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

    @POST
    @Path("/jobs")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(Actors.DISPATCHER)
    JobView submit(@Valid @NotNull SubmitJobRequest body);

    @GET
    @Path("/jobs/{jobId}")
    @RolesAllowed(Actors.DISPATCHER)
    JobView getJob(@PathParam("jobId") UUID jobId);
}
