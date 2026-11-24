package io.freedriver.dashboard.api;

import io.freedriver.dashboard.model.InstanceView;
import io.freedriver.dashboard.model.api.ApplianceCommandListRequest;
import io.freedriver.dashboard.model.api.ApplianceCommandResult;
import io.freedriver.dashboard.model.api.PendingApplianceCommand;
import io.freedriver.mqtt.contract.ApplianceSchemas;
import io.freedriver.mqtt.contract.CanonicalUuid;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

import java.util.List;
import java.util.UUID;

/**
 * Command lists and delayed-command cancels for one house.
 * Paths sit under {@code /api/appliance-commands} so a house appliance named
 * {@code command-lists} or {@code scheduled} still matches {@link AppliancesApi#command}.
 */
@Path("/api/appliance-commands")
@Produces(MediaType.APPLICATION_JSON)
public interface ApplianceCommandsApi {

    /**
     * Sends a list of appliance commands. 200 is one result per entry.
     * {@code confirmed} and {@code timeout} answer an immediate entry (absent or zero delay).
     * {@code scheduled} and {@code sent} answer an entry with a delay. {@code dueAt} is present
     * only on {@code scheduled}.
     * 400 is an invalid body.
     * The house is then checked in this order. The first check that fails answers, and nothing is published:
     * <ol>
     * <li>404: the house is unknown (it has no roster row).</li>
     * <li>409 with an {@code application/json} {@link InstanceView} body whose {@code stale} is true:
     * the house's latest appliance snapshot is stale. The body is the house as it stands.
     * This is the refusal {@link AppliancesApi#command} makes for a stale house.</li>
     * <li>409 with no entity: the house has never sent a scheduled message.</li>
     * <li>404: a target is missing from the house snapshot.</li>
     * </ol>
     * A caller tells the two 409s apart by body. The stale refusal has {@code Content-Type: application/json}
     * and an {@link InstanceView} entity whose {@code stale} is true. The never-scheduled 409 has no entity.
     * Stale is checked before never-scheduled because the never-scheduled 409 sends the caller to
     * {@link AppliancesApi#command}, which refuses a stale house.
     * 429 with an empty body is a spent {@code appliances.commands} window, or a full pending cap.
     * The pending-cap response sets {@link PendingCapHeader#NAME} to {@link PendingCapHeader#FULL}.
     * The spent window leaves that header off.
     */
    @POST
    @Path("/{instanceId}/lists")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({"dashboard", "portal-admin"})
    @RateLimited(RateLimitBucket.APPLIANCES_COMMANDS)
    @APIResponses({
            @APIResponse(responseCode = "200", description = "One result per entry.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(type = SchemaType.ARRAY, implementation = ApplianceCommandResult.class))),
            @APIResponse(responseCode = "400", description = "The body failed validation."),
            @APIResponse(responseCode = "404",
                    description = "Unknown house: the house has no roster row; checked first. "
                            + "Missing target: a target is missing from the house snapshot; checked last, "
                            + "after both 409 checks."),
            @APIResponse(responseCode = "409",
                    description = "Only the stale-snapshot refusal carries the InstanceView body. "
                            + "Stale snapshot: the house's latest appliance snapshot is stale; the body is "
                            + "application/json, an InstanceView with stale true, and nothing is published. "
                            + "Never scheduled: the house has never sent a scheduled message; "
                            + "the response has no entity. "
                            + "The stale check runs before the never-scheduled check, "
                            + "and both run before the target 404.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = InstanceView.class))),
            @APIResponse(responseCode = "429",
                    description = "Empty body. A spent appliances.commands window omits "
                            + PendingCapHeader.NAME + ". A full pending cap sets that header to "
                            + PendingCapHeader.FULL + ".",
                    headers = @Header(name = PendingCapHeader.NAME, description = PendingCapHeader.FULL))
    })
    List<ApplianceCommandResult> commands(
            @PathParam("instanceId") UUID instanceId,
            @Valid @NotNull ApplianceCommandListRequest body);

    /**
     * Pending delayed commands for the house. 200 and an empty list is a house with nothing pending,
     * including a house that has no stored scheduled snapshot.
     */
    @GET
    @Path("/{instanceId}/scheduled")
    @RolesAllowed({"dashboard", "portal-admin"})
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Pending entries. An empty list is valid.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(type = SchemaType.ARRAY, implementation = PendingApplianceCommand.class)))
    })
    List<PendingApplianceCommand> scheduled(@PathParam("instanceId") UUID instanceId);

    /**
     * Dispatches a cancel for every pending entry of this appliance in the house.
     * 204 means the cancel was dispatched to the house controller, including when nothing matched.
     * Controller confirmation arrives on a later message.
     * A missing or blank {@code applianceName} is 400 and cancels nothing.
     * A browser call sends {@code X-CSRF-Token} matching the {@code freedriver-csrf} cookie,
     * the same way the other state-changing dashboard routes do. The dashboard resource enforces it.
     */
    @DELETE
    @Path("/{instanceId}/scheduled")
    @RolesAllowed({"dashboard", "portal-admin"})
    @APIResponses({
            @APIResponse(responseCode = "204",
                    description = "Empty body. The cancel was dispatched, including when nothing matched. "
                            + "A browser call sends X-CSRF-Token matching the freedriver-csrf cookie."),
            @APIResponse(responseCode = "400",
                    description = "applianceName is missing, blank, or longer than the name limit. Nothing is cancelled.")
    })
    void cancelByAppliance(
            @PathParam("instanceId") UUID instanceId,
            @QueryParam("applianceName") @NotBlank @Size(max = ApplianceSchemas.NAME_MAX) String applianceName);

    /**
     * Dispatches a cancel for one pending entry. {@code id} is a canonical UUID.
     * 204 means the cancel was dispatched, including when the id matches nothing.
     * A browser call sends {@code X-CSRF-Token} matching the {@code freedriver-csrf} cookie.
     * The dashboard resource enforces it.
     */
    @DELETE
    @Path("/{instanceId}/scheduled/{id}")
    @RolesAllowed({"dashboard", "portal-admin"})
    @APIResponses({
            @APIResponse(responseCode = "204",
                    description = "Empty body. The cancel was dispatched, including when the id matches nothing. "
                            + "A browser call sends X-CSRF-Token matching the freedriver-csrf cookie."),
            @APIResponse(responseCode = "400", description = "id is not a canonical UUID. Nothing is cancelled.")
    })
    void cancel(
            @PathParam("instanceId") UUID instanceId,
            @PathParam("id") @NotBlank @CanonicalUuid String id);
}
