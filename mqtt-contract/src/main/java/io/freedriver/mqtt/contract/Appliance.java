package io.freedriver.mqtt.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.NonNull;

public record Appliance(
        @JsonProperty(required = true) @NonNull @NotBlank @Size(max = ApplianceSchemas.NAME_MAX) String applianceName,
        @JsonProperty(required = true) boolean state) {}
