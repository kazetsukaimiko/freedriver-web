package io.freedriver.dashboard.model;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Treats a missing delay as {@link Duration#ZERO} when comparing command entries. */
public final class DistinctApplianceDelayValidator
        implements ConstraintValidator<DistinctApplianceDelay, ApplianceCommandListRequest> {

    @Override
    public boolean isValid(ApplianceCommandListRequest value, ConstraintValidatorContext context) {
        if (value == null || value.getCommands() == null) {
            return true;
        }
        Set<NameDelay> seen = new HashSet<>();
        for (ApplianceCommandEntryRequest entry : value.getCommands()) {
            if (entry != null && entry.getApplianceName() != null) {
                Duration delay = entry.getDelay() == null ? Duration.ZERO : entry.getDelay();
                if (!seen.add(new NameDelay(entry.getApplianceName(), delay))) {
                    return false;
                }
            }
        }
        return true;
    }

    private record NameDelay(String applianceName, Duration delay) {}
}
