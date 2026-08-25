package io.freedriver.app.appliances;

import io.freedriver.autonomy.mqtt.contract.Appliance;

import java.util.List;

public record ApplianceMapResponse(
        String lastUpdated, boolean stale, boolean timeout, List<Appliance> appliances) {
}
