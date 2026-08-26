package io.freedriver.app.appliances;

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
        Map<UUID, ApplianceSnapshot> snapshots = control.allKnownInstances();
        Map<UUID, String> names = control.instanceNames();
        for (Map.Entry<UUID, ApplianceSnapshot> entry : snapshots.entrySet()) {
            instances.add(toView(
                    entry.getKey(),
                    names.get(entry.getKey()),
                    entry.getValue(),
                    entry.getValue().stale(config.staleAfter(), now),
                    false));
        }
        return new ApplianceMapResponse(instances);
    }

    public InstanceView issueCommand(@NonNull UUID instanceId, @NonNull String applianceName, boolean on) {
        Instant now = Instant.now();
        Optional<ApplianceSnapshot> maybe = control.instanceSnapshot(instanceId);
        if (maybe.isEmpty()) {
            throw new ApplianceInstanceNotFoundException(instanceId);
        }
        ApplianceSnapshot snapshot = maybe.get();
        if (snapshot.stale(config.staleAfter(), now)) {
            throw new ApplianceStaleException(instanceId, snapshot);
        }
        if (snapshot.find(applianceName).isEmpty()) {
            throw new ApplianceNotFoundException(applianceName);
        }

        String commandId = UUID.randomUUID().toString();
        ApplianceCommandMessage command = new ApplianceCommandMessage(commandId, applianceName, on);
        Duration wait = config.boundedCommandTimeout();
        Optional<ApplianceSnapshot> confirmed = control.publishCommandAndWait(instanceId, command, wait);
        Instant auditedAt = Instant.now();
        String name = control.instanceName(instanceId).orElse(null);
        if (confirmed.isPresent()) {
            audit.record(new ApplianceAudit.Event(auditedAt, instanceId, applianceName, on, commandId, "confirmed"));
            ApplianceSnapshot applied = confirmed.get();
            return toView(instanceId, name, applied, applied.stale(config.staleAfter(), Instant.now()), false);
        }
        audit.record(new ApplianceAudit.Event(auditedAt, instanceId, applianceName, on, commandId, "timeout"));
        ApplianceSnapshot last = control.instanceSnapshot(instanceId).orElse(snapshot);
        return toView(instanceId, name, last, last.stale(config.staleAfter(), Instant.now()), true);
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
