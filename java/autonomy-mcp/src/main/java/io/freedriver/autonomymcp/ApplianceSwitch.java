package io.freedriver.autonomymcp;

import io.freedriver.mcp.OnlyDeclaredFields;
import io.freedriver.mqtt.contract.ApplianceSchemas;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One entry of {@code switch_appliances}: the appliance, the state to set, and an optional wait in
 * seconds before the switch. The constraints are the validation and the advertised item schema, and
 * an entry with any other field is refused.
 */
@OnlyDeclaredFields
public record ApplianceSwitch(
        @NotBlank @Size(max = ApplianceSchemas.NAME_MAX) String applianceName,
        @NotNull Boolean on,
        @Min(0) @Max(SwitchDelay.MAX_DELAY_SECONDS) Integer delaySeconds) {

    /** The wait in seconds; 0 when {@code delaySeconds} is absent. */
    int delay() {
        return delaySeconds == null ? 0 : delaySeconds;
    }
}
