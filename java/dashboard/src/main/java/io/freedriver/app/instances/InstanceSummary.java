package io.freedriver.app.instances;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One house on the list. No password. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InstanceSummary(String instanceId, String label) {
}
