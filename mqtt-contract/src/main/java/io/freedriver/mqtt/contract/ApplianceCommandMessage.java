package io.freedriver.mqtt.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.NonNull;

/**
 * Command body carried on {@link ApplianceSchemas#commandsTopic(java.util.UUID)}.
 * {@code commandId} identifies the command, {@code applianceName} names the appliance,
 * and {@code state} is the requested on/off value.
 */
public record ApplianceCommandMessage(
        @JsonProperty(required = true) @NonNull @NotBlank String commandId,
        @JsonProperty(required = true) @NonNull @NotBlank @Size(max = ApplianceSchemas.NAME_MAX) String applianceName,
        @JsonProperty(required = true) boolean state) {}
