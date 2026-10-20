package io.freedriver.dashboard.model;

import java.util.List;

public record ApplianceMapResponse(List<InstanceView> instances, String csrfToken) {

    public ApplianceMapResponse(List<InstanceView> instances) {
        this(instances, null);
    }
}
