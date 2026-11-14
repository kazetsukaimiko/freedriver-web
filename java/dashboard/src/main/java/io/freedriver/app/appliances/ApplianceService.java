package io.freedriver.app.appliances;

import io.freedriver.dashboard.model.ApplianceMapResponse;
import io.freedriver.dashboard.model.InstanceView;
import io.freedriver.dashboard.model.ApplianceStatus;
import io.freedriver.mqtt.contract.ApplianceCommandMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.NonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ApplianceService {

    private final AppliancesConfig config;
    private final ApplianceControl control;
    private final ApplianceAudit audit;

    @Inject
    public ApplianceService(AppliancesConfig config, ApplianceControl control, ApplianceAudit audit) {
        this.config = config;
        this.control = control;
        this.audit = audit;
    }

    public ApplianceMapResponse currentMap() {
        Instant now = Instant.now();
        List<InstanceView> instances = new ArrayList<>();
        for (Map.Entry<UUID, ApplianceRoster.Row> entry : control.instances().entrySet()) {
            ApplianceSnapshot snapshot = entry.getValue().snapshot();
            instances.add(toView(
                    entry.getKey(),
                    entry.getValue().instanceName(),
                    snapshot,
                    snapshot.stale(config.staleAfter(), now),
                    false));
        }
        return new ApplianceMapResponse(instances);
    }

    public InstanceView issueCommand(
            @NonNull UUID instanceId, @NonNull String applianceName, boolean on, String subject) {
        Instant now = Instant.now();
        Optional<ApplianceRoster.Row> row = control.instance(instanceId);
        if (row.isEmpty()) {
            throw new ApplianceInstanceNotFoundException(instanceId);
        }
        ApplianceSnapshot snapshot = row.get().snapshot();
        String name = row.get().instanceName();
        if (snapshot.stale(config.staleAfter(), now)) {
            throw new ApplianceStaleException(instanceId, snapshot);
        }
        if (snapshot.find(applianceName).isEmpty()) {
            throw new ApplianceNotFoundException(applianceName);
        }

        String commandId = UUID.randomUUID().toString();
        ApplianceCommandMessage command = new ApplianceCommandMessage(commandId, applianceName, on);
        Duration wait = config.boundedCommandTimeout();
        Optional<ApplianceSnapshot> confirmed = control.publishKnownAndWait(instanceId, command, wait);
        Instant auditedAt = Instant.now();
        if (confirmed.isPresent()) {
            audit.record(new ApplianceAudit.Event(
                    auditedAt, instanceId, applianceName, on, commandId, "confirmed", subject));
            ApplianceSnapshot applied = confirmed.get();
            return toView(instanceId, name, applied, applied.stale(config.staleAfter(), Instant.now()), false);
        }
        audit.record(new ApplianceAudit.Event(
                auditedAt, instanceId, applianceName, on, commandId, "timeout", subject));
        return toView(instanceId, name, snapshot, snapshot.stale(config.staleAfter(), Instant.now()), true);
    }

    public InstanceView toView(
            UUID instanceId, String instanceName, ApplianceSnapshot snapshot, boolean stale, boolean timeout) {
        String lastUpdated = snapshot.receivedAt() == null ? null : snapshot.receivedAt().toString();
        List<ApplianceStatus> appliances = snapshot.appliances().stream()
                .map(a -> new ApplianceStatus(a.applianceName(), a.state()))
                .toList();
        return new InstanceView(instanceId, instanceName, lastUpdated, stale, timeout, appliances);
    }
}
