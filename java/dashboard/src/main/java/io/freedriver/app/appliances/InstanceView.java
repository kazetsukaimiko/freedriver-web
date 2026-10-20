package io.freedriver.app.appliances;

import java.util.List;
import java.util.UUID;

public record InstanceView(
        UUID instanceId,
        String instanceName,
        String lastUpdated,
        boolean stale,
        boolean timeout,
        List<ApplianceStatus> appliances) {}
