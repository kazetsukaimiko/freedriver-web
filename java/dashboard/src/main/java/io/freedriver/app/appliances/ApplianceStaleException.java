package io.freedriver.app.appliances;

import java.util.Optional;
import java.util.UUID;

public class ApplianceStaleException extends RuntimeException {

    private final UUID instanceId;
    private final Optional<ApplianceSnapshot> snapshot;

    public ApplianceStaleException(UUID instanceId, ApplianceSnapshot snapshot) {
        this.instanceId = instanceId;
        this.snapshot = snapshot == null ? Optional.empty() : Optional.of(snapshot);
    }

    public UUID instanceId() {
        return instanceId;
    }

    public Optional<ApplianceSnapshot> snapshot() {
        return snapshot;
    }
}
