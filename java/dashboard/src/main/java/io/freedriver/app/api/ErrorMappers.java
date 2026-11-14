package io.freedriver.app.api;

import io.freedriver.app.appliances.ApplianceControl;
import io.freedriver.app.appliances.ApplianceInstanceNotFoundException;
import io.freedriver.app.appliances.ApplianceNotFoundException;
import io.freedriver.app.appliances.ApplianceService;
import io.freedriver.app.appliances.ApplianceSnapshot;
import io.freedriver.app.appliances.ApplianceStaleException;
import io.freedriver.app.instances.BrokerRefusedException;
import io.freedriver.app.instances.InstanceFailure;
import io.freedriver.app.instances.InstanceNotFoundException;
import io.freedriver.app.instances.InvalidLabelException;
import io.freedriver.app.invites.BadInviteException;
import io.freedriver.app.invites.InviteUpstreamException;
import io.freedriver.app.jobs.DispatcherUnavailable;
import io.freedriver.app.security.RateLimitedException;
import io.freedriver.dashboard.model.InstanceView;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.util.List;
import java.util.Map;

/** Every dashboard exception the REST layer turns into a status, in one place. */
@ApplicationScoped
public class ErrorMappers {

    @Inject
    ApplianceService appliances;

    @Inject
    ApplianceControl control;

    /** 401 on the API is exactly the empty JSON object {@code {}}. Ahead of the built-in security mapper. */
    @ServerExceptionMapper(priority = 1)
    public Response unauthorized(UnauthorizedException exception) {
        return Response.status(Response.Status.UNAUTHORIZED).type(MediaType.APPLICATION_JSON_TYPE).entity(Map.of()).build();
    }

    /** 403 on the API is exactly the empty JSON object {@code {}}. Ahead of the built-in security mapper. */
    @ServerExceptionMapper(priority = 1)
    public Response forbidden(ForbiddenException exception) {
        return Response.status(Response.Status.FORBIDDEN).type(MediaType.APPLICATION_JSON_TYPE).entity(Map.of()).build();
    }

    @ServerExceptionMapper
    public Response rateLimited(RateLimitedException exception) {
        return Response.status(429).build();
    }

    @ServerExceptionMapper
    public Response applianceNotFound(ApplianceNotFoundException exception) {
        return Response.status(404).build();
    }

    @ServerExceptionMapper
    public Response applianceInstanceNotFound(ApplianceInstanceNotFoundException exception) {
        return Response.status(404).build();
    }

    /** 409 with the instance as it stands, marked stale. */
    @ServerExceptionMapper
    public Response applianceStale(ApplianceStaleException exception) {
        ApplianceSnapshot snapshot = exception.snapshot().orElse(new ApplianceSnapshot(null, List.of()));
        String name = exception.instanceId() == null
                ? null
                : control.instanceName(exception.instanceId()).orElse(null);
        InstanceView body = appliances.toView(exception.instanceId(), name, snapshot, true, false);
        return Response.status(409).type(MediaType.APPLICATION_JSON).entity(body).build();
    }

    @ServerExceptionMapper
    public Response label(InvalidLabelException exception) {
        return json(400, InstanceFailure.label());
    }

    @ServerExceptionMapper
    public Response broker(BrokerRefusedException exception) {
        return json(502, InstanceFailure.broker());
    }

    @ServerExceptionMapper
    public Response missing(InstanceNotFoundException exception) {
        return json(404, InstanceFailure.missing(exception.instanceId()));
    }

    @ServerExceptionMapper
    public Response badInvite(BadInviteException exception) {
        return Response.status(400).type(MediaType.APPLICATION_JSON_TYPE).entity("{}").build();
    }

    @ServerExceptionMapper
    public Response inviteUpstream(InviteUpstreamException exception) {
        return Response.status(502).type(MediaType.APPLICATION_JSON_TYPE).entity(Map.of("error", "unavailable")).build();
    }

    @ServerExceptionMapper
    public Response dispatcherUnavailable(DispatcherUnavailable exception) {
        return Response.status(502).type(MediaType.APPLICATION_JSON_TYPE).entity(Map.of("error", "unavailable")).build();
    }

    private static Response json(int status, InstanceFailure body) {
        return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE).entity(body).build();
    }
}
