package io.freedriver.mqtt.contract;

import java.time.Duration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Switch one target now, or after {@code delay}. An absent delay means now.
 * {@code state} is a JSON boolean; a string is refused.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplianceCommandEntry(
        @JsonProperty(required = true) @NotBlank @CanonicalUuid String id,
        @JsonProperty(required = true) @NotBlank @Size(min = 1, max = ApplianceSchemas.NAME_MAX) String target,
        @JsonProperty(required = true) @NotNull Boolean state,
        @DelayWindow Duration delay) implements CommandListEntry {

    @Override
    public CommandEntryType type() {
        return CommandEntryType.APPLIANCE;
    }
}
