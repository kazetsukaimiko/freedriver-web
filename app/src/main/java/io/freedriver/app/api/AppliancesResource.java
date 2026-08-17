package io.freedriver.app.api;

import io.freedriver.app.appliances.ApplianceCommandRequest;
import io.freedriver.app.appliances.ApplianceMapResponse;
import io.freedriver.app.appliances.ApplianceService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/appliances")
@Produces(MediaType.APPLICATION_JSON)
public class AppliancesResource {

    @Inject
    ApplianceService appliances;

    @Inject
    SecurityIdentity identity;

    @GET
    @RolesAllowed({"dashboard", "portal-admin"})
    public ApplianceMapResponse list() {
        appliances.assertCanAccess(identity);
        return appliances.currentMap();
    }

    @POST
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({"dashboard", "portal-admin"})
    public ApplianceMapResponse command(@PathParam("id") String id, ApplianceCommandRequest body) {
        appliances.assertCanAccess(identity);
        if (body == null) {
            throw new BadRequestException("JSON body required");
        }
        body.assertValid();
        return appliances.issueCommand(identity, id, body.getOn());
    }
}
