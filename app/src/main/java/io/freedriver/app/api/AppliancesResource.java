package io.freedriver.app.api;

import io.freedriver.app.appliances.ApplianceCommandRequest;
import io.freedriver.app.appliances.ApplianceMapResponse;
import io.freedriver.app.appliances.ApplianceService;
import io.freedriver.autonomy.mqtt.contract.ApplianceSchemas;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

    @GET
    @RolesAllowed({"dashboard", "portal-admin"})
    public ApplianceMapResponse list() {
        return appliances.currentMap();
    }

    @POST
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({"dashboard", "portal-admin"})
    public ApplianceMapResponse command(
            @PathParam("id") @NotBlank @Size(max = ApplianceSchemas.NAME_MAX) String id,
            @Valid @NotNull ApplianceCommandRequest body) {
        return appliances.issueCommand(id, body.getOn());
    }
}
