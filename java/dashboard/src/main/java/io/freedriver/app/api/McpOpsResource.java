package io.freedriver.app.api;

import io.freedriver.app.appliances.ApplianceService;
import io.freedriver.app.appliances.MqttLiveClient;
import io.freedriver.dashboard.api.McpOpsApi;
import io.freedriver.dashboard.model.ApplianceMapResponse;
import io.freedriver.dashboard.model.HomeStatusResponse;
import io.freedriver.dashboard.model.InstanceView;
import io.freedriver.app.schedule.PendingCapException;
import io.freedriver.app.schedule.ScheduledSwitch;
import io.freedriver.app.schedule.ScheduledSwitchRules;
import io.freedriver.app.schedule.ScheduledSwitchRepository;
import io.freedriver.app.schedule.ScheduledSwitchesConfig;
import io.freedriver.app.security.OidcSubjects;
import io.freedriver.dashboard.model.McpDebugResponse;
import io.freedriver.dashboard.model.ScheduledSwitchRequest;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.UUID;

@ApplicationScoped
@Path("/api/mcp")
public class McpOpsResource implements McpOpsApi {

    @Inject
    ApplianceService appliances;

    @Inject
    MqttLiveClient mqtt;

    @Inject
    SecurityIdentity identity;

    @Inject
    ScheduledSwitchRepository switches;

    @Inject
    ScheduledSwitchesConfig scheduledSwitches;

    @Override
    public ApplianceMapResponse appliances() {
        return appliances.currentMap();
    }

    @Override
    public HomeStatusResponse homeStatus() {
        ApplianceMapResponse map = appliances.currentMap();
        boolean stale = false;
        String lastUpdated = null;
        if (map.instances() != null) {
            for (InstanceView instance : map.instances()) {
                stale = stale || instance.stale();
                if (instance.lastUpdated() != null
                        && (lastUpdated == null || instance.lastUpdated().compareTo(lastUpdated) > 0)) {
                    lastUpdated = instance.lastUpdated();
                }
            }
        }
        return new HomeStatusResponse(mqtt.brokerConnected(), lastUpdated, stale);
    }

    @Override
    public McpDebugResponse debug() {
        HomeStatusResponse status = homeStatus();
        String category = !status.brokerConnected() ? "broker-down" : status.stale() ? "stale" : "none";
        return new McpDebugResponse("UP", category);
    }

    @Override
    public Response schedule(ScheduledSwitchRequest body) {
        String subject = OidcSubjects.subject(identity);
        Instant now = Instant.now();
        ScheduledSwitchRules.check(
                body.getInstanceId(), body.getApplianceName(), body.getDueAt(), subject, now);
        ScheduledSwitch held = switches.storeHeld(new ScheduledSwitch(
                UUID.randomUUID().toString(),
                body.getInstanceId().toString(),
                body.getApplianceName(),
                body.getOn(),
                body.getDueAt(),
                subject), now);
        long count;
        try {
            count = switches.countBySubject(subject);
        } catch (RuntimeException ex) {
            switches.deleteById(held.id);
            throw ex;
        }
        if (count > scheduledSwitches.maxPending()) {
            switches.deleteById(held.id);
            throw new PendingCapException();
        }
        switches.release(held.id);
        return Response.accepted().build();
    }
}
