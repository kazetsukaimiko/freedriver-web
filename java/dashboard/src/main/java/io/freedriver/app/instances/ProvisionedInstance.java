package io.freedriver.app.instances;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Create and open response. {@code password} is the credential just set.
 * {@link #toString()} omits it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProvisionedInstance(String instanceId, String label, String password) {

    @Override
    public String toString() {
        return "ProvisionedInstance{instanceId=" + instanceId + ", label=" + label + "}";
    }
}
