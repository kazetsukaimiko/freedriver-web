package io.freedriver.app.appliances;

public class ApplianceNotFoundException extends RuntimeException {

    public ApplianceNotFoundException(String applianceName) {
        super(applianceName);
    }
}
