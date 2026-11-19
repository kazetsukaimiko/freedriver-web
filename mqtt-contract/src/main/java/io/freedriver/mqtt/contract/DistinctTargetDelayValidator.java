package io.freedriver.mqtt.contract;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Treats a missing delay as {@link Duration#ZERO} when comparing appliance entries. */
public final class DistinctTargetDelayValidator
        implements ConstraintValidator<DistinctTargetDelay, ApplianceCommandList> {

    @Override
    public boolean isValid(ApplianceCommandList value, ConstraintValidatorContext context) {
        if (value == null || value.commands() == null) {
            return true;
        }
        Set<TargetDelay> seen = new HashSet<>();
        for (CommandListEntry entry : value.commands()) {
            if (entry instanceof ApplianceCommandEntry appliance && appliance.target() != null) {
                Duration delay = appliance.delay() == null ? Duration.ZERO : appliance.delay();
                if (!seen.add(new TargetDelay(appliance.target(), delay))) {
                    return false;
                }
            }
        }
        return true;
    }

    private record TargetDelay(String target, Duration delay) {}
}
