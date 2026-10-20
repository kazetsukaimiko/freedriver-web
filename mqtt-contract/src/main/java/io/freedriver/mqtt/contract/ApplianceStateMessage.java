package io.freedriver.mqtt.contract;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.NonNull;

/**
 * State body carried on {@link ApplianceSchemas#appliancesTopic(java.util.UUID)}.
 * {@code instanceName} is the display name, and {@code appliedCommandId} echoes the
 * command being confirmed.
 */
public record ApplianceStateMessage(
        @JsonProperty(required = true) @NonNull @NotBlank String instanceName,
        String appliedCommandId,
        @JsonProperty(required = true) @NonNull @NotNull @Valid List<Appliance> appliances) {

    public ApplianceStateMessage {
        appliances = List.copyOf(appliances);
    }
}
