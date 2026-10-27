package io.freedriver.app.instances;

import io.freedriver.dashboard.api.RateLimited;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.ResponseStatus;

/**
 * Portal-admin house provision. {@code @Size} on {@link CreateInstance#label()} is the
 * same max {@link HouseProvision} enforces. The failure body is cause {@code label},
 * which bean validation does not write, so the resource checks the length itself.
 */
@Path("/api/instances")
@Produces(MediaType.APPLICATION_JSON)
public interface InstancesApi {

    @GET
    @RolesAllowed("portal-admin")
    InstanceList list();

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("portal-admin")
    @RateLimited("instances")
    @ResponseStatus(201)
    ProvisionedInstance create(CreateInstance request);

    @POST
    @Path("{instanceId}/open")
    @RolesAllowed("portal-admin")
    @RateLimited("instances")
    ProvisionedInstance open(@PathParam("instanceId") String instanceId);
}
