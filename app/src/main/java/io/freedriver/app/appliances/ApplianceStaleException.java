package io.freedriver.app.appliances;

import java.util.Optional;

/**
 * POST against a stale or absent map. Carries the snapshot (or empty).
 * The mapper owns HTTP 409 and serializes {@link ApplianceMapResponse}.
 */
public class ApplianceStaleException extends RuntimeException {

    private final Optional<ApplianceSnapshot> snapshot;

    public ApplianceStaleException(Optional<ApplianceSnapshot> snapshot) {
        this.snapshot = snapshot == null ? Optional.empty() : snapshot;
    }

    public Optional<ApplianceSnapshot> snapshot() {
        return snapshot;
    }
}
