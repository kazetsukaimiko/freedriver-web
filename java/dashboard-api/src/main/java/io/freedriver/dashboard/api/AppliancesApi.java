package io.freedriver.dashboard.api;

import io.freedriver.dashboard.model.ApplianceCommandRequest;
import io.freedriver.dashboard.model.ApplianceMapResponse;
import io.freedriver.dashboard.model.InstanceView;
import io.freedriver.mqtt.contract.ApplianceSchemas;
import jakarta.annotation.security.RolesAllowed;
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
public interface AppliancesApi {

    @GET
    @RolesAllowed({"dashboard", "portal-admin"})
    ApplianceMapResponse list();

    @POST
    @Path("/{instanceId}/{applianceName}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({"dashboard", "portal-admin"})
    @RateLimited("appliances.commands")
    InstanceView command(
            @PathParam("instanceId") UUID instanceId,
            @PathParam("applianceName") @NotBlank @Size(max = ApplianceSchemas.NAME_MAX) String applianceName,
            @Valid @NotNull ApplianceCommandRequest body);
}
