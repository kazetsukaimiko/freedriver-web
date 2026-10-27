package io.freedriver.app.instances;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Users in {@code mqtt-instance}, plus the CSRF token for create and open. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InstanceList(List<InstanceSummary> instances, String csrfToken) {
}
