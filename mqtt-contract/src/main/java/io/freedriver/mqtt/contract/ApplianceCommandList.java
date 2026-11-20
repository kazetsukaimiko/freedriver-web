package io.freedriver.mqtt.contract;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Command list carried on {@link ApplianceSchemas#commandListsTopic(java.util.UUID)}.
 * {@code listId} names the list, {@code issuedAt} is when the sender created it, and
 * {@code commands} run in order.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@DistinctEntryIds
@DistinctTargetDelay
public record ApplianceCommandList(
        @JsonProperty(required = true) @NotBlank @CanonicalUuid String listId,
        @JsonProperty(required = true) @NotNull Instant issuedAt,
        @JsonProperty(required = true) @NotNull @Size(min = 1, max = 32) @Valid
        List<@NotNull @Valid CommandListEntry> commands) {

    public ApplianceCommandList {
        if (commands != null) {
            commands = List.copyOf(commands);
        }
    }
}
