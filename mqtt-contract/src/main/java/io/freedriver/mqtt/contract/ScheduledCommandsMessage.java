package io.freedriver.mqtt.contract;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The controller's complete pending set, carried on {@link ApplianceSchemas#scheduledTopic(java.util.UUID)}.
 * {@code pending} empty means nothing is waiting.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@DistinctPendingIds
public record ScheduledCommandsMessage(
        @JsonProperty(required = true) @NotNull Instant publishedAt,
        @JsonProperty(required = true) @NotNull @Size(max = ApplianceSchemas.MAX_PENDING) @Valid
        List<@NotNull @Valid PendingCommand> pending) {

    public ScheduledCommandsMessage {
        if (pending != null) {
            pending = List.copyOf(pending);
        }
    }
}
