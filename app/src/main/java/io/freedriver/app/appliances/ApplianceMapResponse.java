package io.freedriver.app.appliances;

import java.util.List;

public record ApplianceMapResponse(
        String lastUpdated, boolean stale, boolean timeout, List<Appliance> appliances) {
}
