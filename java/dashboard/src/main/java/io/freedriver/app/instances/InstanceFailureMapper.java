package io.freedriver.app.instances;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

@ApplicationScoped
public class InstanceFailureMapper {

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

    private static Response json(int status, InstanceFailure body) {
        return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE).entity(body).build();
    }
}
