package io.freedriver.mqtt.contract;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One delayed command still waiting on the controller. {@code dueAt} is when it fires.
 * {@code state} is a JSON boolean.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PendingCommand(
        @JsonProperty(required = true) @NotBlank @CanonicalUuid String id,
        @JsonProperty(required = true) @NotBlank @Size(min = 1, max = ApplianceSchemas.NAME_MAX) String target,
        @JsonProperty(required = true) @NotNull Boolean state,
        @JsonProperty(required = true) @NotNull Instant dueAt) {}
