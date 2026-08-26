package io.freedriver.app.appliances;

/** Unknown appliance alias. The mapper owns HTTP 404. */
public class ApplianceNotFoundException extends RuntimeException {

    public ApplianceNotFoundException(String applianceName) {
        super(applianceName);
    }
}
