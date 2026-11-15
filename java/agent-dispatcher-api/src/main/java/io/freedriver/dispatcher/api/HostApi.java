package io.freedriver.dispatcher.api;

import io.freedriver.dispatcher.model.AssignmentView;
import io.freedriver.dispatcher.model.DeviceView;
import io.freedriver.dispatcher.model.FinishRequest;
import io.freedriver.dispatcher.model.HostPaths;
import io.freedriver.dispatcher.model.HostReport;
import io.freedriver.dispatcher.model.StartRequest;
import io.freedriver.dispatcher.model.TakeRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

/**
 * The host's door: a program publishes its roster, takes each job exactly once, says where it
 * runs, and posts its result. Its caller is a service with its own credentials, so this contract
 * carries no role: the dispatcher's resource class requires {@code device}. The device is the
 * caller's token.
 */
@Path(HostPaths.ROOT)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface HostApi {

    /** Publishes this host's bots. */
    @POST
    @Path(HostPaths.REPORT)
    DeviceView report(@Valid @NotNull HostReport body);

    /**
     * Takes this host's jobs. A queued job is returned {@code CLAIMED}; a claimed or running job
     * whose hold has lapsed is returned again with its state. Waits up to {@code waitSeconds} when
     * none is ready.
     */
    @POST
    @Path(HostPaths.TAKE)
    List<AssignmentView> take(@Valid @NotNull TakeRequest body);

    /**
     * Moves one claimed job to {@code RUNNING} and stores {@code deliveryRef}. The same call again
     * with the same reference returns it unchanged; another reference is 409.
     */
    @POST
    @Path(HostPaths.START)
    AssignmentView start(@PathParam(HostPaths.ASSIGNMENT_ID) UUID assignmentId,
                         @Valid @NotNull StartRequest body);

    /** Posts the result of one job. */
    @POST
    @Path(HostPaths.FINISH)
    AssignmentView finish(@PathParam(HostPaths.ASSIGNMENT_ID) UUID assignmentId,
                          @Valid @NotNull FinishRequest body);
}
