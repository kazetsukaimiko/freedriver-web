package io.freedriver.app.appliances;

import java.util.List;

public record ApplianceMapResponse(List<InstanceView> instances, String csrfToken) {

    public ApplianceMapResponse(List<InstanceView> instances) {
        this(instances, null);
    }
}
