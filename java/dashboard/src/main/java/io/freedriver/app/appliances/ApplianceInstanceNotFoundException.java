package io.freedriver.app.appliances;

import java.util.UUID;

public class ApplianceInstanceNotFoundException extends RuntimeException {

    public ApplianceInstanceNotFoundException(UUID instanceId) {
        super(instanceId == null ? "" : instanceId.toString());
    }
}
