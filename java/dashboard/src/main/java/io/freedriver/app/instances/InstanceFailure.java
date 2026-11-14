package io.freedriver.app.instances;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Failure body for create and open. {@code instanceId} is set only when {@code cause} is {@code missing}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InstanceFailure(String type, String cause, String instanceId) {

    public static InstanceFailure label() {
        return new InstanceFailure("instance", "label", null);
    }

    public static InstanceFailure broker() {
        return new InstanceFailure("instance", "broker", null);
    }

    public static InstanceFailure missing(String instanceId) {
        return new InstanceFailure("instance", "missing", instanceId);
    }
}
