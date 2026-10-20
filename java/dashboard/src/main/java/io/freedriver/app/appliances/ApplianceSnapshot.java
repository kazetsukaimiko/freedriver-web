package io.freedriver.app.appliances;

import io.freedriver.mqtt.contract.Appliance;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** In-memory map for one autonomy instance. Looked up by instanceId; not a wire DTO. */
public record ApplianceSnapshot(Instant receivedAt, List<Appliance> appliances) {

    public ApplianceSnapshot {
        appliances = appliances == null ? List.of() : List.copyOf(appliances);
    }

    public boolean stale(Duration window, Instant now) {
        if (receivedAt == null) {
            return true;
        }
        return Duration.between(receivedAt, now).compareTo(window) >= 0;
    }

    public Optional<Appliance> find(String applianceName) {
        return appliances.stream().filter(a -> a.applianceName().equals(applianceName)).findFirst();
    }
}
