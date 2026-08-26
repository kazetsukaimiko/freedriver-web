package io.freedriver.app.appliances;

import io.freedriver.autonomy.mqtt.contract.Appliance;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public record ApplianceSnapshot(
        Instant receivedAt,
        UUID instanceId,
        String instanceName,
        String appliedCommandId,
        List<Appliance> appliances) {

    public boolean stale(Duration window, Instant now) {
        if (receivedAt == null) {
            return true;
        }
        return Duration.between(receivedAt, now).compareTo(window) >= 0;
    }

    public Optional<Appliance> find(String applianceName) {
        return appliances.stream().filter(a -> a.applianceName().equals(applianceName)).findFirst();
    }

    public ApplianceMapResponse toResponse(boolean stale, boolean timeout) {
        String lastUpdated = receivedAt == null ? null : receivedAt.toString();
        return new ApplianceMapResponse(lastUpdated, stale, timeout, appliances);
    }
}
