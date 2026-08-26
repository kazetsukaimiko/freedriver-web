package io.freedriver.mqtt.contract;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.NonNull;

/**
 * Topic A body: {@code freedriver/v1/{instanceId}/appliances} (retain=false, QoS 1).
 * {@code instanceId} is the topic segment, not a JSON field.
 * {@code instanceName} is UX-only. {@code appliedCommandId} is the command
 * handshake for waiters; it is not part of the portal snapshot.
 */
public record ApplianceStateMessage(
        @JsonProperty(required = true) @NonNull @NotBlank String instanceName,
        String appliedCommandId,
        @JsonProperty(required = true) @NonNull @NotNull @Valid List<Appliance> appliances) {

    public ApplianceStateMessage {
        appliances = List.copyOf(appliances);
    }
}
