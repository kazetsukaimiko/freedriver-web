package io.freedriver.mqtt.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Cancel every pending delayed command for {@code target}, or the one pending entry named
 * {@code cancelId}. A cancel has no state and no delay; a present JSON value for either is an
 * unknown field and is refused.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@OneCancelSelector
public record CancelCommandEntry(
        @JsonProperty(required = true) @NotBlank @CanonicalUuid String id,
        @Size(min = 1, max = ApplianceSchemas.NAME_MAX) String target,
        @CanonicalUuid String cancelId) implements CommandListEntry {

    @Override
    public CommandEntryType type() {
        return CommandEntryType.CANCEL;
    }
}
