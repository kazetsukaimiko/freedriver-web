package io.freedriver.dashboard.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.freedriver.mqtt.contract.ApplianceSchemas;
import io.freedriver.mqtt.contract.CanonicalUuid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * One POST result. {@code dueAt} is present only when {@code outcome} is {@code scheduled}.
 * {@code confirmed} and {@code timeout} answer an immediate entry (absent or zero delay).
 * {@code scheduled} and {@code sent} answer an entry that carries a delay.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplianceCommandResult(
        @NotBlank @CanonicalUuid String id,
        @NotBlank @Size(min = 1, max = ApplianceSchemas.NAME_MAX) String applianceName,
        @NotNull CommandListOutcome outcome,
        Instant dueAt) {

    @AssertTrue(message = "dueAt is present only when outcome is scheduled")
    @JsonIgnore
    public boolean isDueAtOnlyForScheduled() {
        if (outcome == null) {
            return true;
        }
        return (outcome == CommandListOutcome.SCHEDULED) == (dueAt != null);
    }
}
