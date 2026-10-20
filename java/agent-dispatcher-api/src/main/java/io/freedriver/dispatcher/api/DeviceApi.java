package io.freedriver.dispatcher.api;

import io.freedriver.dispatcher.model.AssignmentView;
import io.freedriver.dispatcher.model.DeviceView;
import io.freedriver.dispatcher.model.RegisterDeviceRequest;
import io.freedriver.dispatcher.model.UpdateAssignmentRequest;
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

@Path("/api/device")
@Produces(MediaType.APPLICATION_JSON)
public interface DeviceApi {

    @POST
    @Path("/register")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(Actors.DEVICE)
    DeviceView register(@Valid @NotNull RegisterDeviceRequest body);

    @POST
    @Path("/heartbeat")
    @RolesAllowed(Actors.DEVICE)
    DeviceView heartbeat();

    @GET
    @Path("/assignments")
    @RolesAllowed(Actors.DEVICE)
    List<AssignmentView> pollAssignments();

    @GET
    @Path("/assignments/{assignmentId}")
    @RolesAllowed(Actors.DEVICE)
    AssignmentView assignment(@PathParam("assignmentId") UUID assignmentId);

    @POST
    @Path("/assignments/{assignmentId}/claim")
    @RolesAllowed(Actors.DEVICE)
    AssignmentView claim(@PathParam("assignmentId") UUID assignmentId);

    @POST
    @Path("/assignments/{assignmentId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(Actors.DEVICE)
    AssignmentView update(
            @PathParam("assignmentId") UUID assignmentId,
            @Valid @NotNull UpdateAssignmentRequest body);
}
