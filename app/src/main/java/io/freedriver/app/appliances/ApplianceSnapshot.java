package io.freedriver.app.appliances;

import io.freedriver.autonomy.mqtt.contract.Appliance;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public record ApplianceSnapshot(Instant receivedAt, String appliedCommandId, List<Appliance> appliances) {

    public static ApplianceSnapshot never() {
        return new ApplianceSnapshot(null, null, List.of());
    }

    public boolean stale(Duration window, Instant now) {
        if (receivedAt == null) {
            return true;
        }
        return Duration.between(receivedAt, now).compareTo(window) >= 0;
    }

    public Optional<Appliance> find(String name) {
        return appliances.stream().filter(a -> a.name().equals(name)).findFirst();
    }

    public ApplianceMapResponse toResponse(boolean stale, boolean timeout) {
        String lastUpdated = receivedAt == null ? null : receivedAt.toString();
        return new ApplianceMapResponse(lastUpdated, stale, timeout, appliances);
    }
}
