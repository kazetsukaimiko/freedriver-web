package io.freedriver.app.api;

import io.freedriver.app.appliances.ApplianceCommandRequest;
import io.freedriver.app.appliances.ApplianceMapResponse;
import io.freedriver.app.appliances.ApplianceService;
import io.freedriver.app.appliances.InstanceView;
import io.freedriver.app.security.RateLimited;
import io.freedriver.mqtt.contract.ApplianceSchemas;
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

import java.util.UUID;

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
    @Path("/{instanceId}/{applianceName}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({"dashboard", "portal-admin"})
    @RateLimited("appliances.commands")
    public InstanceView command(
            @PathParam("instanceId") UUID instanceId,
            @PathParam("applianceName") @NotBlank @Size(max = ApplianceSchemas.NAME_MAX) String applianceName,
            @Valid @NotNull ApplianceCommandRequest body) {
        return appliances.issueCommand(instanceId, applianceName, body.getOn());
    }
}
