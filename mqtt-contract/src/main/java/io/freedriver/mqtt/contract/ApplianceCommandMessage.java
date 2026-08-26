package io.freedriver.mqtt.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.NonNull;

/**
 * Topic B body: {@code freedriver/v1/{instanceId}/commands} (retain=false, QoS 1).
 * {@code instanceId} is the topic segment, not a JSON field.
 */
public record ApplianceCommandMessage(
        @JsonProperty(required = true) @NonNull @NotBlank String commandId,
        @JsonProperty(required = true) @NonNull @NotBlank @Size(max = ApplianceSchemas.NAME_MAX) String applianceName,
        @JsonProperty(required = true) boolean state) {}
