package io.freedriver.app.appliances;

import java.util.Optional;

public class ApplianceStaleException extends RuntimeException {

    private final Optional<ApplianceSnapshot> snapshot;

    public ApplianceStaleException(Optional<ApplianceSnapshot> snapshot) {
        this.snapshot = snapshot == null ? Optional.empty() : snapshot;
    }

    public Optional<ApplianceSnapshot> snapshot() {
        return snapshot;
    }
}
