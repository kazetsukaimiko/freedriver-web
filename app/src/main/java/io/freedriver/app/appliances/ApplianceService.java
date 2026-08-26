package io.freedriver.app.appliances;

import io.freedriver.autonomy.mqtt.contract.ApplianceCommandMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.NonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ApplianceService {

    private final AppliancesConfig config;
    private final ApplianceBackend backend;
    private final ApplianceAudit audit;

    @Inject
    public ApplianceService(AppliancesConfig config, ApplianceBackend backend, ApplianceAudit audit) {
        this.config = config;
        this.backend = backend;
        this.audit = audit;
    }

    public ApplianceMapResponse currentMap() {
        Instant now = Instant.now();
        return backend.snapshot()
                .map(snapshot -> snapshot.toResponse(snapshot.stale(config.staleAfter(), now), false))
                .orElseGet(() -> new ApplianceMapResponse(null, true, false, List.of()));
    }

    public ApplianceMapResponse issueCommand(@NonNull String applianceName, boolean on) {
        Instant now = Instant.now();
        Optional<ApplianceSnapshot> maybe = backend.snapshot();
        if (maybe.isEmpty() || maybe.get().stale(config.staleAfter(), now)) {
            throw new ApplianceStaleException(maybe);
        }
        ApplianceSnapshot snapshot = maybe.get();
        if (snapshot.instanceId() == null) {
            throw new IllegalStateException("Non-stale appliance snapshot is missing instanceId");
        }
        if (snapshot.find(applianceName).isEmpty()) {
            throw new ApplianceNotFoundException(applianceName);
        }

        String commandId = UUID.randomUUID().toString();
        ApplianceCommandMessage command = new ApplianceCommandMessage(
                snapshot.instanceId(), commandId, applianceName, on);
        backend.publishCommand(command);

        Duration wait = config.boundedCommandTimeout();
        Optional<ApplianceSnapshot> confirmed = backend.awaitApplied(commandId, wait);
        Instant auditedAt = Instant.now();
        if (confirmed.isPresent()) {
            audit.record(new ApplianceAudit.Event(auditedAt, applianceName, on, commandId, "confirmed"));
            ApplianceSnapshot applied = confirmed.get();
            return applied.toResponse(applied.stale(config.staleAfter(), Instant.now()), false);
        }
        audit.record(new ApplianceAudit.Event(auditedAt, applianceName, on, commandId, "timeout"));
        return backend.snapshot()
                .map(last -> last.toResponse(last.stale(config.staleAfter(), Instant.now()), true))
                .orElseGet(() -> new ApplianceMapResponse(null, true, true, List.of()));
    }
}
