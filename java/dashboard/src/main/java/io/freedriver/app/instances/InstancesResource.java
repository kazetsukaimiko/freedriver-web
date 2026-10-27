package io.freedriver.app.instances;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
@Path("/api/instances")
@RolesAllowed("portal-admin")
public class InstancesResource implements InstancesApi {

    private final HouseProvision houses;

    @Inject
    public InstancesResource(HouseProvision houses) {
        this.houses = houses;
    }

    @Override
    public InstanceList list() {
        return houses.list();
    }

    @Override
    public Response create(CreateInstance request) {
        String label = request == null ? null : request.label();
        ProvisionedInstance created = houses.create(label);
        return Response.status(Response.Status.CREATED)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(created)
                .build();
    }

    @Override
    public ProvisionedInstance open(String instanceId) {
        return houses.open(instanceId);
    }
}
