package io.freedriver.app.instances;

/** Open's lookup found no user in {@code mqtt-instance} with that username. */
public class InstanceNotFoundException extends RuntimeException {

    private final String instanceId;

    public InstanceNotFoundException(String instanceId) {
        super("missing");
        this.instanceId = instanceId;
    }

    public String instanceId() {
        return instanceId;
    }
}
